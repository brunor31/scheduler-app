package com.greentech.scheduler_app.domain.model;

import com.greentech.scheduler_app.domain.exception.InvalidScheduleException;

import java.time.Instant;
import java.util.Objects;

public record Schedule(
		String tenantId,
		String scheduleId,
		long version,
		Instant dueAt,
		ScheduleState state,
		String targetType,
		String payload,
		Instant createdAt,
		Instant updatedAt,
		Instant deliveredAt
) {

	public Schedule {
		requireText(tenantId, "tenantId");
		requireText(scheduleId, "scheduleId");
		if (version < 1) {
			throw new InvalidScheduleException("version must be at least 1");
		}
		Objects.requireNonNull(dueAt, "dueAt must not be null");
		Objects.requireNonNull(state, "state must not be null");
		requireText(targetType, "targetType");
		Objects.requireNonNull(payload, "payload must not be null");
		Objects.requireNonNull(createdAt, "createdAt must not be null");
		Objects.requireNonNull(updatedAt, "updatedAt must not be null");
	}

	public static Schedule create(String tenantId, String scheduleId, Instant dueAt, String payload, Instant now) {
		Objects.requireNonNull(now, "now must not be null");
		if (dueAt == null || !dueAt.isAfter(now)) {
			throw new InvalidScheduleException("dueAt must be in the future");
		}
		return new Schedule(
				tenantId,
				scheduleId,
				1,
				dueAt,
				ScheduleState.SCHEDULED,
				"FAKE",
				payload == null ? "{}" : payload,
				now,
				now,
				null
		);
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new InvalidScheduleException(field + " must not be blank");
		}
	}
}
