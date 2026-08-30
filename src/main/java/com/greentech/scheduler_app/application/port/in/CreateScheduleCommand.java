package com.greentech.scheduler_app.application.port.in;

import java.time.Instant;

public record CreateScheduleCommand(
		String tenantId,
		String scheduleId,
		Instant dueAt,
		String payload
) {
}
