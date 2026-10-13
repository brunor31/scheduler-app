package com.greentech.scheduler_app.application.exception;

public class ScheduleVersionConflictException extends RuntimeException {

	public ScheduleVersionConflictException(String tenantId, String scheduleId, long expectedVersion) {
		super("Schedule version conflict: " + tenantId + "/" + scheduleId
				+ ", expected version " + expectedVersion);
	}
}
