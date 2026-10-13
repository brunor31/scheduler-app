package com.greentech.scheduler_app.application.service;

import com.greentech.scheduler_app.application.exception.ScheduleVersionConflictException;
import com.greentech.scheduler_app.application.port.in.CancelScheduleCommand;
import com.greentech.scheduler_app.application.port.in.RescheduleScheduleCommand;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleTimer;
import com.greentech.scheduler_app.domain.model.Schedule;
import com.greentech.scheduler_app.domain.model.ScheduleState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleMutationServicesTest {

	private static final Instant NOW = Instant.parse("2026-08-29T18:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC);

	@Test
	void persistsRescheduleBeforePublishingNewVersionToTimer() {
		RecordingRepository repository = new RecordingRepository(scheduled());
		RecordingTimer timer = new RecordingTimer(repository);
		RescheduleScheduleService service = new RescheduleScheduleService(repository, timer, CLOCK);

		Schedule result = service.reschedule(new RescheduleScheduleCommand(
				"tenant-a", "schedule-1", 1, NOW.plusSeconds(60)));

		assertThat(result.version()).isEqualTo(2);
		assertThat(repository.current).isSameAs(result);
		assertThat(timer.scheduled).isSameAs(result);
		assertThat(timer.repositoryWasUpdatedFirst).isTrue();
	}

	@Test
	void persistsCancellationWithoutPublishingAnotherTimer() {
		RecordingRepository repository = new RecordingRepository(scheduled());
		CancelScheduleService service = new CancelScheduleService(repository, CLOCK);

		Schedule result = service.cancel(new CancelScheduleCommand("tenant-a", "schedule-1", 1));

		assertThat(result.version()).isEqualTo(2);
		assertThat(result.state()).isEqualTo(ScheduleState.CANCELLED);
		assertThat(repository.current).isSameAs(result);
	}

	@Test
	void rejectsStaleExpectedVersion() {
		RecordingRepository repository = new RecordingRepository(scheduled().reschedule(
				NOW.plusSeconds(60), NOW.plusSeconds(5)));
		CancelScheduleService service = new CancelScheduleService(repository, CLOCK);

		assertThatThrownBy(() -> service.cancel(new CancelScheduleCommand("tenant-a", "schedule-1", 1)))
				.isInstanceOf(ScheduleVersionConflictException.class)
				.hasMessageContaining("expected version 1");
	}

	private static Schedule scheduled() {
		return Schedule.create("tenant-a", "schedule-1", NOW.plusSeconds(30), "{}", NOW);
	}

	private static final class RecordingRepository extends com.greentech.scheduler_app.support.StubScheduleRepository {

		private Schedule current;

		private RecordingRepository(Schedule current) {
			this.current = current;
		}

		@Override
		public void insert(Schedule schedule) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<Schedule> findById(String tenantId, String scheduleId) {
			return Optional.ofNullable(current);
		}

		@Override
		public List<Schedule> findRecoverableDueBefore(Instant horizon, int limit) {
			return List.of();
		}

		@Override
		public boolean update(Schedule schedule, long expectedVersion) {
			if (current == null || current.version() != expectedVersion || current.state() != ScheduleState.SCHEDULED) {
				return false;
			}
			current = schedule;
			return true;
		}

		@Override
		public Optional<Schedule> tryClaim(String tenantId, String scheduleId, long expectedVersion,
				UUID attemptToken, Duration leaseDuration) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean markDelivered(String tenantId, String scheduleId, long expectedVersion, UUID attemptToken, Instant deliveredAt) {
			return false;
		}
	}

	private static final class RecordingTimer implements ScheduleTimer {

		private final RecordingRepository repository;
		private Schedule scheduled;
		private boolean repositoryWasUpdatedFirst;

		private RecordingTimer(RecordingRepository repository) {
			this.repository = repository;
		}

		@Override
		public void schedule(Schedule schedule) {
			scheduled = schedule;
			repositoryWasUpdatedFirst = repository.current == schedule;
		}
	}
}
