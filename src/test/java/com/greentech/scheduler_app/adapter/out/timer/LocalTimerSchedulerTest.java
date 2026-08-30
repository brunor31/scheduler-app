package com.greentech.scheduler_app.adapter.out.timer;

import com.greentech.scheduler_app.application.port.in.HandleDueScheduleUseCase;
import com.greentech.scheduler_app.domain.model.Schedule;
import com.greentech.scheduler_app.domain.model.ScheduleState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTimerSchedulerTest {

	private static final Instant NOW = Instant.parse("2026-08-29T18:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void limitsParallelDeliveryAndAppliesBackpressure() throws InterruptedException {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		BlockingHandler handler = new BlockingHandler(2, 10);
		LocalTimerScheduler scheduler = new LocalTimerScheduler(handler, CLOCK, meterRegistry, 2, 2);

		for (int index = 0; index < 10; index++) {
			scheduler.schedule(dueSchedule("schedule-" + index));
		}

		try {
			scheduler.start();
			assertThat(handler.firstWaveEntered.await(1, TimeUnit.SECONDS)).isTrue();
			awaitCondition(() -> gauge(meterRegistry, "scheduler.delivery.queue.size") == 2, Duration.ofSeconds(1));

			assertThat(gauge(meterRegistry, "scheduler.delivery.in.progress")).isEqualTo(2);
			assertThat(gauge(meterRegistry, "scheduler.delivery.queue.size")).isEqualTo(2);
			assertThat(gauge(meterRegistry, "scheduler.timer.queue.size")).isGreaterThan(0);

			handler.release.countDown();
			assertThat(handler.completed.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(handler.maximumConcurrency.get()).isEqualTo(2);
			assertThat(handler.calls.get()).isEqualTo(10);
		} finally {
			handler.release.countDown();
			scheduler.stop();
		}
	}

	@Test
	void keepsSameVersionDeduplicatedWhileDeliveryIsInProgress() throws InterruptedException {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		BlockingHandler handler = new BlockingHandler(1, 1);
		LocalTimerScheduler scheduler = new LocalTimerScheduler(handler, CLOCK, meterRegistry, 1, 1);
		Schedule schedule = dueSchedule("same-version");

		try {
			scheduler.start();
			scheduler.schedule(schedule);
			assertThat(handler.firstWaveEntered.await(1, TimeUnit.SECONDS)).isTrue();

			scheduler.schedule(schedule);
			assertThat(gauge(meterRegistry, "scheduler.timer.active.keys")).isEqualTo(1);

			handler.release.countDown();
			assertThat(handler.completed.await(1, TimeUnit.SECONDS)).isTrue();
			awaitCondition(
					() -> gauge(meterRegistry, "scheduler.timer.active.keys") == 0
							&& gauge(meterRegistry, "scheduler.delivery.in.progress") == 0
							&& gauge(meterRegistry, "scheduler.delivery.queue.size") == 0,
					Duration.ofSeconds(1)
			);

			assertThat(handler.calls.get()).isEqualTo(1);
		} finally {
			handler.release.countDown();
			scheduler.stop();
		}
	}

	@Test
	void rejectsNonPositiveWorkerAndQueueConfiguration() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		HandleDueScheduleUseCase handler = (tenantId, scheduleId, version) -> {
		};

		assertThatThrownBy(() -> new LocalTimerScheduler(handler, CLOCK, meterRegistry, 0, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("deliveryWorkerCount must be at least 1");
		assertThatThrownBy(() -> new LocalTimerScheduler(handler, CLOCK, meterRegistry, 1, 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("deliveryQueueCapacity must be at least 1");
	}

	private static Schedule dueSchedule(String scheduleId) {
		return new Schedule(
				"tenant-a",
				scheduleId,
				1,
				NOW,
				ScheduleState.SCHEDULED,
				"FAKE",
				"{}",
				NOW.minusSeconds(1),
				NOW.minusSeconds(1),
				null
		);
	}

	private static double gauge(SimpleMeterRegistry meterRegistry, String name) {
		return meterRegistry.get(name).gauge().value();
	}

	private static void awaitCondition(BooleanSupplier condition, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(condition.getAsBoolean()).isTrue();
	}

	private static final class BlockingHandler implements HandleDueScheduleUseCase {

		private final CountDownLatch firstWaveEntered;
		private final CountDownLatch completed;
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicInteger currentConcurrency = new AtomicInteger();
		private final AtomicInteger maximumConcurrency = new AtomicInteger();

		private BlockingHandler(int firstWaveSize, int expectedCompletions) {
			this.firstWaveEntered = new CountDownLatch(firstWaveSize);
			this.completed = new CountDownLatch(expectedCompletions);
		}

		@Override
		public void handle(String tenantId, String scheduleId, long version) {
			calls.incrementAndGet();
			int concurrent = currentConcurrency.incrementAndGet();
			maximumConcurrency.accumulateAndGet(concurrent, Math::max);
			firstWaveEntered.countDown();
			try {
				release.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} finally {
				currentConcurrency.decrementAndGet();
				completed.countDown();
			}
		}
	}
}
