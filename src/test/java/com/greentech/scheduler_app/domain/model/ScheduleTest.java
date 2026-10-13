package com.greentech.scheduler_app.domain.model;

import com.greentech.scheduler_app.domain.exception.InvalidScheduleException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleTest {

	private static final Instant NOW = Instant.parse("2026-08-29T18:00:00Z");

	@Test
	void createsInitialScheduledVersion() {
		Schedule schedule = Schedule.create("tenant-a", "schedule-1", NOW.plusSeconds(30), "{}", NOW);

		assertThat(schedule.version()).isEqualTo(1);
		assertThat(schedule.state()).isEqualTo(ScheduleState.SCHEDULED);
		assertThat(schedule.targetType()).isEqualTo("FAKE");
		assertThat(schedule.createdAt()).isEqualTo(NOW);
		assertThat(schedule.deliveredAt()).isNull();
	}

	@Test
	void rejectsScheduleThatIsNotInTheFuture() {
		assertThatThrownBy(() -> Schedule.create("tenant-a", "schedule-1", NOW, "{}", NOW))
				.isInstanceOf(InvalidScheduleException.class)
				.hasMessage("dueAt must be in the future");
	}

	@Test
	void reschedulesByIncrementingVersionAndPreservingIdentity() {
		Schedule original = Schedule.create("tenant-a", "schedule-1", NOW.plusSeconds(30), "{}", NOW);
		Instant changedAt = NOW.plusSeconds(5);
		Instant newDueAt = NOW.plusSeconds(60);

		Schedule rescheduled = original.reschedule(newDueAt, changedAt);

		assertThat(rescheduled.version()).isEqualTo(2);
		assertThat(rescheduled.dueAt()).isEqualTo(newDueAt);
		assertThat(rescheduled.state()).isEqualTo(ScheduleState.SCHEDULED);
		assertThat(rescheduled.createdAt()).isEqualTo(NOW);
		assertThat(rescheduled.updatedAt()).isEqualTo(changedAt);
		assertThat(rescheduled.cancelledAt()).isNull();
	}

	@Test
	void cancelsByIncrementingVersionAndRecordingTimestamp() {
		Schedule original = Schedule.create("tenant-a", "schedule-1", NOW.plusSeconds(30), "{}", NOW);
		Instant cancelledAt = NOW.plusSeconds(5);

		Schedule cancelled = original.cancel(cancelledAt);

		assertThat(cancelled.version()).isEqualTo(2);
		assertThat(cancelled.state()).isEqualTo(ScheduleState.CANCELLED);
		assertThat(cancelled.cancelledAt()).isEqualTo(cancelledAt);
		assertThat(cancelled.updatedAt()).isEqualTo(cancelledAt);
	}

	@Test
	void rejectsMutationAfterCancellation() {
		Schedule cancelled = Schedule.create("tenant-a", "schedule-1", NOW.plusSeconds(30), "{}", NOW)
				.cancel(NOW.plusSeconds(5));

		assertThatThrownBy(() -> cancelled.reschedule(NOW.plusSeconds(60), NOW.plusSeconds(10)))
				.isInstanceOf(InvalidScheduleException.class)
				.hasMessage("cannot reschedule schedule in state CANCELLED");
		assertThatThrownBy(() -> cancelled.cancel(NOW.plusSeconds(10)))
				.isInstanceOf(InvalidScheduleException.class)
				.hasMessage("cannot cancel schedule in state CANCELLED");
	}
}
