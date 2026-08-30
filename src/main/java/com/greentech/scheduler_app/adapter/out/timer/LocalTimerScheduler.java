package com.greentech.scheduler_app.adapter.out.timer;

import com.greentech.scheduler_app.application.port.in.HandleDueScheduleUseCase;
import com.greentech.scheduler_app.application.port.out.ScheduleTimer;
import com.greentech.scheduler_app.domain.model.Schedule;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LocalTimerScheduler implements ScheduleTimer, SmartLifecycle {

	private static final Logger LOGGER = LoggerFactory.getLogger(LocalTimerScheduler.class);

	private final DelayQueue<TimerEntry> timerQueue = new DelayQueue<>();
	private final BlockingQueue<TimerEntry> deliveryQueue;
	private final Set<TimerKey> activeKeys = ConcurrentHashMap.newKeySet();
	private final AtomicInteger deliveriesInProgress = new AtomicInteger();
	private final HandleDueScheduleUseCase dueScheduleHandler;
	private final Clock clock;
	private final int deliveryWorkerCount;
	private volatile boolean running;
	private ExecutorService timerCoordinator;
	private ExecutorService deliveryWorkers;

	public LocalTimerScheduler(
			HandleDueScheduleUseCase dueScheduleHandler,
			Clock clock,
			MeterRegistry meterRegistry,
			@Value("${scheduler.timer.delivery-workers:1}") int deliveryWorkerCount,
			@Value("${scheduler.timer.delivery-queue-capacity:1000}") int deliveryQueueCapacity
	) {
		if (deliveryWorkerCount < 1) {
			throw new IllegalArgumentException("deliveryWorkerCount must be at least 1");
		}
		if (deliveryQueueCapacity < 1) {
			throw new IllegalArgumentException("deliveryQueueCapacity must be at least 1");
		}
		this.dueScheduleHandler = dueScheduleHandler;
		this.clock = clock;
		this.deliveryWorkerCount = deliveryWorkerCount;
		this.deliveryQueue = new ArrayBlockingQueue<>(deliveryQueueCapacity);
		Gauge.builder("scheduler.timer.queue.size", timerQueue, DelayQueue::size).register(meterRegistry);
		Gauge.builder("scheduler.delivery.queue.size", deliveryQueue, BlockingQueue::size).register(meterRegistry);
		Gauge.builder("scheduler.delivery.in.progress", deliveriesInProgress, AtomicInteger::get).register(meterRegistry);
		Gauge.builder("scheduler.timer.active.keys", activeKeys, Set::size).register(meterRegistry);
	}

	@Override
	public void schedule(Schedule schedule) {
		TimerKey key = new TimerKey(schedule.tenantId(), schedule.scheduleId(), schedule.version());
		if (!activeKeys.add(key)) {
			return;
		}
		long delayNanos = Math.max(0, safeNanos(Duration.between(clock.instant(), schedule.dueAt())));
		try {
			timerQueue.add(new TimerEntry(key, System.nanoTime() + delayNanos));
		} catch (RuntimeException exception) {
			activeKeys.remove(key);
			throw exception;
		}
	}

	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		timerCoordinator = Executors.newSingleThreadExecutor(
				Thread.ofPlatform().name("local-timer-coordinator").factory()
		);
		deliveryWorkers = Executors.newFixedThreadPool(
				deliveryWorkerCount,
				Thread.ofPlatform().name("local-delivery-worker-", 0).factory()
		);
		for (int index = 0; index < deliveryWorkerCount; index++) {
			deliveryWorkers.submit(this::runDeliveryLoop);
		}
		timerCoordinator.submit(this::runTimerLoop);
	}

	@Override
	public synchronized void stop() {
		running = false;
		if (timerCoordinator != null) {
			shutdown(timerCoordinator);
			timerCoordinator = null;
		}
		if (deliveryWorkers != null) {
			shutdown(deliveryWorkers);
			deliveryWorkers = null;
		}
		timerQueue.clear();
		deliveryQueue.clear();
		activeKeys.clear();
		deliveriesInProgress.set(0);
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void runTimerLoop() {
		while (running && !Thread.currentThread().isInterrupted()) {
			try {
				TimerEntry entry = timerQueue.take();
				deliveryQueue.put(entry);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void runDeliveryLoop() {
		while (running && !Thread.currentThread().isInterrupted()) {
			try {
				TimerEntry entry = deliveryQueue.take();
				deliver(entry);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void deliver(TimerEntry entry) {
		deliveriesInProgress.incrementAndGet();
		try {
			dueScheduleHandler.handle(entry.key().tenantId(), entry.key().scheduleId(), entry.key().version());
		} catch (RuntimeException exception) {
			LOGGER.error("Local timer execution failed", exception);
		} finally {
			deliveriesInProgress.decrementAndGet();
			activeKeys.remove(entry.key());
		}
	}

	private static long safeNanos(Duration duration) {
		try {
			return duration.toNanos();
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE / 2;
		}
	}

	private static void shutdown(ExecutorService executor) {
		executor.shutdownNow();
		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				LOGGER.warn("Local timer executor did not stop within five seconds");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private record TimerKey(String tenantId, String scheduleId, long version) {
	}

	private record TimerEntry(TimerKey key, long deadlineNanos) implements Delayed {

		@Override
		public long getDelay(TimeUnit unit) {
			return unit.convert(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
		}

		@Override
		public int compareTo(Delayed other) {
			TimerEntry entry = (TimerEntry) other;
			return Long.compare(deadlineNanos, entry.deadlineNanos);
		}
	}
}
