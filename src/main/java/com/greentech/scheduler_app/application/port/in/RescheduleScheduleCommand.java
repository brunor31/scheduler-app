package com.greentech.scheduler_app.application.port.in;

import java.time.Instant;

public record RescheduleScheduleCommand(
		String tenantId,
		String scheduleId,
		long expectedVersion,
		Instant dueAt
) {
}
