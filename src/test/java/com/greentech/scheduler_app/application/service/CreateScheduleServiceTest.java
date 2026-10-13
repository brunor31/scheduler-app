package com.greentech.scheduler_app.application.service;

import com.greentech.scheduler_app.application.port.in.CreateScheduleCommand;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleTimer;
import com.greentech.scheduler_app.domain.model.Schedule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreateScheduleServiceTest {

	@Test
	void persistsBeforePublishingTheTimer() {
		Instant now = Instant.parse("2026-08-29T18:00:00Z");
		RecordingRepository repository = new RecordingRepository();
		RecordingTimer timer = new RecordingTimer(repository);
		CreateScheduleService service = new CreateScheduleService(
				repository,
				timer,
				Clock.fixed(now, ZoneOffset.UTC)
		);

		Schedule created = service.create(new CreateScheduleCommand(
				"tenant-a",
				"schedule-1",
				now.plusSeconds(30),
				"{\"value\":1}"
		));

		assertThat(repository.inserted).isSameAs(created);
		assertThat(timer.scheduled).isSameAs(created);
		assertThat(timer.repositoryWasWrittenFirst).isTrue();
	}

	private static final class RecordingRepository extends com.greentech.scheduler_app.support.StubScheduleRepository {

		private Schedule inserted;

		@Override
		public void insert(Schedule schedule) {
			inserted = schedule;
		}

		@Override
		public Optional<Schedule> findById(String tenantId, String scheduleId) {
			return Optional.empty();
		}

		@Override
		public List<Schedule> findRecoverableDueBefore(Instant horizon, int limit) {
			return List.of();
		}

		@Override
		public boolean update(Schedule schedule, long expectedVersion) {
			return false;
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
		private boolean repositoryWasWrittenFirst;

		private RecordingTimer(RecordingRepository repository) {
			this.repository = repository;
		}

		@Override
		public void schedule(Schedule schedule) {
			scheduled = schedule;
			repositoryWasWrittenFirst = repository.inserted == schedule;
		}
	}
}
