package com.greentech.scheduler_app.application.port.in;

public record CancelScheduleCommand(
		String tenantId,
		String scheduleId,
		long expectedVersion
) {
}
