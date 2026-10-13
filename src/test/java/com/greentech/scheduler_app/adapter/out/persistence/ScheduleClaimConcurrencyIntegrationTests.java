package com.greentech.scheduler_app.adapter.out.persistence;

import com.greentech.scheduler_app.adapter.out.timer.ScheduleRecoveryLoader;
import com.greentech.scheduler_app.application.exception.ScheduleStateConflictException;
import com.greentech.scheduler_app.application.port.in.CancelScheduleCommand;
import com.greentech.scheduler_app.application.port.in.RescheduleScheduleCommand;
import com.greentech.scheduler_app.application.port.out.ScheduleTarget;
import com.greentech.scheduler_app.application.service.CancelScheduleService;
import com.greentech.scheduler_app.application.service.HandleDueScheduleService;
import com.greentech.scheduler_app.application.service.RescheduleScheduleService;
import com.greentech.scheduler_app.domain.model.Schedule;
import com.greentech.scheduler_app.domain.model.ScheduleState;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ScheduleClaimConcurrencyIntegrationTests {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
	private static final Duration LEASE = Duration.ofMinutes(1);
	private static JdbcClient jdbc;
	private JdbcScheduleRepository repository;
	private Schedule original;

	@BeforeAll
	static void migrate() {
		var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		Flyway.configure().dataSource(dataSource).load().migrate();
		jdbc = JdbcClient.create(dataSource);
	}

	@BeforeEach
	void setup() {
		jdbc.sql("TRUNCATE TABLE schedule").update();
		repository = new JdbcScheduleRepository(jdbc);
		Instant now = Instant.now();
		original = Schedule.create("tenant-a", "schedule-1", now.minusSeconds(1), "{}", now.minusSeconds(2));
		repository.insert(original);
	}

	@RepeatedTest(20)
	void onlyOneConcurrentWorkerAcquiresLiveClaim() throws Exception {
		assertOneWinner(() -> claim(UUID.randomUUID()), () -> claim(UUID.randomUUID()));
		assertThat(stored().state()).isEqualTo(ScheduleState.CLAIMED);
	}

	@RepeatedTest(20)
	void cancelAndClaimCannotBothWin() throws Exception {
		Schedule cancelled = original.cancel(Instant.now());
		assertOneWinner(() -> repository.update(cancelled, 1), () -> claim(UUID.randomUUID()));
		Schedule finalState = stored();
		assertThat(finalState.state()).isIn(ScheduleState.CANCELLED, ScheduleState.CLAIMED);
		assertThat(finalState.version()).isEqualTo(finalState.state() == ScheduleState.CANCELLED ? 2 : 1);
	}

	@RepeatedTest(20)
	void rescheduleAndClaimCannotBothWin() throws Exception {
		Schedule rescheduled = original.reschedule(Instant.now().plusSeconds(3600), Instant.now());
		assertOneWinner(() -> repository.update(rescheduled, 1), () -> claim(UUID.randomUUID()));
		Schedule finalState = stored();
		if (finalState.state() == ScheduleState.SCHEDULED) {
			assertThat(finalState.version()).isEqualTo(2);
			assertThat(claim(UUID.randomUUID())).isFalse();
		} else {
			assertThat(finalState.state()).isEqualTo(ScheduleState.CLAIMED);
			assertThat(finalState.version()).isEqualTo(1);
		}
	}

	@RepeatedTest(20)
	void cancelAndRescheduleCannotBothWin() throws Exception {
		assertOneWinner(() -> repository.update(original.cancel(Instant.now()), 1),
				() -> repository.update(original.reschedule(Instant.now().plusSeconds(3600), Instant.now()), 1));
		assertThat(stored().version()).isEqualTo(2);
		assertThat(claim(UUID.randomUUID())).isFalse();
	}

	@RepeatedTest(20)
	void competingReschedulesCannotOverwriteEachOther() throws Exception {
		assertOneWinner(() -> repository.update(original.reschedule(Instant.now().plusSeconds(3600), Instant.now()), 1),
				() -> repository.update(original.reschedule(Instant.now().plusSeconds(7200), Instant.now()), 1));
		assertThat(stored().version()).isEqualTo(2);
	}

	@Test
	void rejectsWrongTenantStaleVersionFutureAndCancelledSchedules() {
		assertThat(repository.tryClaim("other-tenant", "schedule-1", 1, UUID.randomUUID(), LEASE)).isEmpty();
		assertThat(repository.tryClaim("tenant-a", "schedule-1", 2, UUID.randomUUID(), LEASE)).isEmpty();
		Schedule future = original.reschedule(Instant.now().plusSeconds(3600), Instant.now());
		assertThat(repository.update(future, 1)).isTrue();
		assertThat(repository.tryClaim("tenant-a", "schedule-1", 2, UUID.randomUUID(), LEASE)).isEmpty();
		assertThat(repository.update(future.cancel(Instant.now()), 2)).isTrue();
		assertThat(repository.tryClaim("tenant-a", "schedule-1", 3, UUID.randomUUID(), LEASE)).isEmpty();
	}

	@Test
	void recoversExpiredClaimAndRejectsOldAttemptCompletion() {
		UUID oldToken = UUID.randomUUID();
		UUID newToken = UUID.randomUUID();
		assertThat(claim(oldToken)).isTrue();
		assertThat(claim(newToken)).isFalse();
		assertThat(recoverable()).isEmpty();
		assertThat(repository.markDelivered("tenant-a", "schedule-1", 1, newToken, Instant.now())).isFalse();

		expireClaim();
		assertThat(repository.markDelivered("tenant-a", "schedule-1", 1, oldToken, Instant.now())).isFalse();
		List<Schedule> loaded = new ArrayList<>();
		new ScheduleRecoveryLoader(repository, loaded::add, Clock.systemUTC(), Duration.ofMinutes(15), 100)
				.loadUpcomingSchedules();
		assertThat(loaded).extracting(Schedule::scheduleId).containsExactly("schedule-1");
		assertThat(claim(newToken)).isTrue();
		assertThat(repository.markDelivered("tenant-a", "schedule-1", 1, oldToken, Instant.now())).isFalse();
		assertThat(repository.markDelivered("tenant-a", "schedule-1", 1, newToken, Instant.now())).isTrue();
		assertThat(repository.markDelivered("tenant-a", "schedule-1", 1, newToken, Instant.now())).isFalse();
		assertThat(stored().state()).isEqualTo(ScheduleState.DELIVERED);
		assertThat(claim(UUID.randomUUID())).isFalse();
		assertThat(recoverable()).isEmpty();
	}

	@Test
	void liveDispatchBlocksSecondHandlerAndBothMutations() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		ScheduleTarget target = schedule -> {
			calls.incrementAndGet();
			entered.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
		};
		try (var executor = Executors.newSingleThreadExecutor()) {
			var first = executor.submit(() -> handler(target).handle("tenant-a", "schedule-1", 1));
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				handler(target).handle("tenant-a", "schedule-1", 1);
				assertThatThrownBy(() -> new CancelScheduleService(repository, Clock.systemUTC())
						.cancel(new CancelScheduleCommand("tenant-a", "schedule-1", 1)))
						.isInstanceOf(ScheduleStateConflictException.class);
				assertThatThrownBy(() -> new RescheduleScheduleService(repository, schedule -> {}, Clock.systemUTC())
						.reschedule(new RescheduleScheduleCommand("tenant-a", "schedule-1", 1, Instant.now().plusSeconds(60))))
						.isInstanceOf(ScheduleStateConflictException.class);
				assertThat(calls).hasValue(1);
			} finally {
				release.countDown();
			}
			first.get(10, TimeUnit.SECONDS);
		}
		assertThat(stored().state()).isEqualTo(ScheduleState.DELIVERED);
	}

	@Test
	void returnsStateConflictWhenClaimWinsAfterMutationRead() {
		JdbcScheduleRepository racingRepository = new JdbcScheduleRepository(jdbc) {
			@Override
			public boolean update(Schedule schedule, long expectedVersion) {
				assertThat(claim(UUID.randomUUID())).isTrue();
				return super.update(schedule, expectedVersion);
			}
		};
		assertThatThrownBy(() -> new CancelScheduleService(racingRepository, Clock.systemUTC())
				.cancel(new CancelScheduleCommand("tenant-a", "schedule-1", 1)))
				.isInstanceOf(ScheduleStateConflictException.class);
	}

	@Test
	void persistsUnknownOutcomeUntilRetryDeadlineThenAllowsRecovery() {
		AtomicInteger calls = new AtomicInteger();
		ScheduleTarget target = schedule -> {
			if (calls.incrementAndGet() == 1) throw new IllegalStateException("ACK lost");
		};
		var handler = handler(target);
		handler.handle("tenant-a", "schedule-1", 1);
        assertThat(stored().state()).isEqualTo(ScheduleState.RETRY_WAIT);
        handler.handle("tenant-a", "schedule-1", 1);
        assertThat(calls).hasValue(1);
        jdbc.sql("UPDATE schedule SET next_attempt_at=clock_timestamp()-interval '1 second'").update();
        handler.handle("tenant-a", "schedule-1", 1);
		assertThat(calls).hasValue(2);
		assertThat(stored().state()).isEqualTo(ScheduleState.DELIVERED);
	}

	@Test
	void returnsStateConflictWhenClaimWinsAfterRescheduleRead() {
		JdbcScheduleRepository racingRepository = new JdbcScheduleRepository(jdbc) {
			@Override
			public boolean update(Schedule schedule, long expectedVersion) {
				assertThat(claim(UUID.randomUUID())).isTrue();
				return super.update(schedule, expectedVersion);
			}
		};
		assertThatThrownBy(() -> new RescheduleScheduleService(racingRepository, schedule -> {}, Clock.systemUTC())
				.reschedule(new RescheduleScheduleCommand("tenant-a", "schedule-1", 1, Instant.now().plusSeconds(60))))
				.isInstanceOf(ScheduleStateConflictException.class);
	}

	@Test
	void recoversAfterTargetSuccessBeforeDatabaseConfirmation() {
		AtomicInteger calls = new AtomicInteger();
		JdbcScheduleRepository failedConfirmation = new JdbcScheduleRepository(jdbc) {
			@Override
			public boolean markDelivered(String tenantId, String scheduleId, long version, UUID token, Instant deliveredAt) {
				throw new IllegalStateException("process lost before confirmation");
			}
		};
		var firstHandler = new HandleDueScheduleService(failedConfirmation, schedule -> calls.incrementAndGet(),
				(lag, duration) -> {}, Clock.systemUTC(), LEASE);
		assertThatThrownBy(() -> firstHandler.handle("tenant-a", "schedule-1", 1))
				.isInstanceOf(IllegalStateException.class);
		assertThat(calls).hasValue(1);
		assertThat(stored().state()).isEqualTo(ScheduleState.CLAIMED);

		expireClaim();
		handler(schedule -> calls.incrementAndGet()).handle("tenant-a", "schedule-1", 1);
		assertThat(calls).hasValue(2); // At-least-once: a database claim cannot deduplicate the target side effect.
		assertThat(stored().state()).isEqualTo(ScheduleState.DELIVERED);
	}

	private boolean claim(UUID token) {
		return repository.tryClaim("tenant-a", "schedule-1", 1, token, LEASE).isPresent();
	}

	private Schedule stored() {
		return repository.findById("tenant-a", "schedule-1").orElseThrow();
	}

	private List<Schedule> recoverable() {
		return repository.findRecoverableDueBefore(Instant.now().plusSeconds(900), 100);
	}

	private void expireClaim() {
		jdbc.sql("UPDATE schedule SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE state = 'CLAIMED'")
				.update();
	}

	private HandleDueScheduleService handler(ScheduleTarget target) {
		return new HandleDueScheduleService(repository, target, (lag, duration) -> {}, Clock.systemUTC(), LEASE);
	}

	private static void assertOneWinner(Callable<Boolean> left, Callable<Boolean> right) throws Exception {
		CyclicBarrier start = new CyclicBarrier(3);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return left.call(); });
			var b = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return right.call(); });
			start.await(10, TimeUnit.SECONDS);
			assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
		}
	}
}
