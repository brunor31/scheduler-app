package com.greentech.scheduler_app.adapter.out.timer;

import com.greentech.scheduler_app.application.port.in.HandleDueScheduleUseCase;
import com.greentech.scheduler_app.application.port.out.ScheduleTimer;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.infrastructure.config.TimerProperties;
import org.springframework.beans.factory.annotation.Autowired;
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
	private final java.util.Map<String, TimerEntry> queuedBySchedule = new ConcurrentHashMap<>();
	private final Set<TimerKey> activeKeys = ConcurrentHashMap.newKeySet();
	private final AtomicInteger deliveriesInProgress = new AtomicInteger();
	private final HandleDueScheduleUseCase dueScheduleHandler;
	private final Clock clock;
	private final int deliveryWorkerCount;
	@org.springframework.beans.factory.annotation.Value("${scheduler.migration-only:false}")
    private boolean migrationOnly;
    @Override public boolean isAutoStartup() { return !migrationOnly; }
    private volatile boolean running;
    private volatile boolean accepting = true;
    private final TimerProperties properties;
    private final ScheduleRepository repository;
	private ExecutorService timerCoordinator;
	private ExecutorService deliveryWorkers;

	public LocalTimerScheduler(HandleDueScheduleUseCase handler, Clock clock, MeterRegistry registry, int workers, int capacity) {
        this(handler,clock,registry,workers,capacity,new TimerProperties(null,null,1000,100000,100,null),null);
    }

    @Autowired
    public LocalTimerScheduler(HandleDueScheduleUseCase handler, Clock clock, MeterRegistry registry,
            @Value("${scheduler.timer.delivery-workers:4}") int workers,
            @Value("${scheduler.timer.delivery-queue-capacity:1000}") int capacity,
            TimerProperties properties, ScheduleRepository repository) {
        if(workers<1) throw new IllegalArgumentException("deliveryWorkerCount must be at least 1");
        if(capacity<1) throw new IllegalArgumentException("deliveryQueueCapacity must be at least 1");
        this.dueScheduleHandler=handler; this.clock=clock; this.deliveryWorkerCount=workers;
        this.deliveryQueue=new ArrayBlockingQueue<>(capacity); this.properties=properties; this.repository=repository;
        Gauge.builder("scheduler.timer.queue.size",timerQueue,DelayQueue::size).register(registry);
        Gauge.builder("scheduler.delivery.queue.size",deliveryQueue,BlockingQueue::size).register(registry);
        Gauge.builder("scheduler.delivery.in.progress",deliveriesInProgress,AtomicInteger::get).register(registry);
        Gauge.builder("scheduler.timer.active.keys",activeKeys,Set::size).register(registry);
    }

	@Override
    public synchronized void schedule(Schedule schedule) {
        if(!accepting || schedule.state().terminal() || schedule.effectiveDueAt().isAfter(clock.instant().plus(properties.lookAhead()))) return;
        TimerKey key=new TimerKey(schedule.tenantId(),schedule.scheduleId(),schedule.version());
        if(activeKeys.contains(key)) return;
        // Remove superseded entries still waiting locally; correctness still belongs to the database CAS.
        String identity = identity(key);
        TimerEntry older = queuedBySchedule.get(identity);
        if (older != null && older.key().version() > key.version()) return;
        if (older != null && older.key().version() < key.version()
                && (timerQueue.remove(older) || deliveryQueue.remove(older))) activeKeys.remove(older.key());
        if(activeKeys.size()>=properties.maxLoaded() || !activeKeys.add(key)) return;
        long delay=Math.max(0,safeNanos(Duration.between(clock.instant(),schedule.effectiveDueAt())));
        TimerEntry entry = new TimerEntry(key,System.nanoTime()+delay,schedule.effectiveDueAt());
        queuedBySchedule.put(identity,entry);
        timerQueue.add(entry);
    }

    private static String identity(TimerKey key) { return key.tenantId() + ":" + key.scheduleId(); }

    @Override public int remainingCapacity() { return Math.max(0,properties.maxLoaded()-activeKeys.size()); }

	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
        accepting = true;
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
    public void stop() {
        accepting=false; running=false;
        if(timerCoordinator!=null) { timerCoordinator.shutdownNow(); timerCoordinator=null; }
        if(deliveryWorkers!=null) {
            deliveryWorkers.shutdown();
            try {
                if(!deliveryWorkers.awaitTermination(properties.drainTimeout().toMillis(),TimeUnit.MILLISECONDS)) deliveryWorkers.shutdownNow();
            } catch(InterruptedException exception) { Thread.currentThread().interrupt(); deliveryWorkers.shutdownNow(); }
            deliveryWorkers=null;
        }
        timerQueue.clear(); deliveryQueue.clear(); activeKeys.clear(); queuedBySchedule.clear();
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
		while ((running || !deliveryQueue.isEmpty()) && !Thread.currentThread().isInterrupted()) {
			try {
				TimerEntry entry = deliveryQueue.poll(100, TimeUnit.MILLISECONDS);
				if (entry != null) deliver(entry);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void deliver(TimerEntry entry) {
        queuedBySchedule.remove(identity(entry.key()), entry);
		deliveriesInProgress.incrementAndGet();
		try {
			dueScheduleHandler.handle(entry.key().tenantId(), entry.key().scheduleId(), entry.key().version());
		} catch (RuntimeException exception) {
			LOGGER.error("Local timer execution failed: {}", exception.getClass().getSimpleName());
		} finally {
			deliveriesInProgress.decrementAndGet();
			activeKeys.remove(entry.key());
            if(running && repository!=null) {
                try {
                    repository.findById(entry.key().tenantId(),entry.key().scheduleId())
                        .filter(s -> s.state().mutable() && (s.version() > entry.key().version()
                            || s.effectiveDueAt().isAfter(entry.effectiveDueAt()))).ifPresent(this::schedule);
                } catch(RuntimeException exception) {
                    LOGGER.warn("Timer reload deferred to reconciliation: {}",exception.getClass().getSimpleName());
                }
            }
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

	private record TimerEntry(TimerKey key, long deadlineNanos, java.time.Instant effectiveDueAt) implements Delayed {

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
