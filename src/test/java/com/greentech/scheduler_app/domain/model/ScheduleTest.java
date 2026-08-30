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
}
