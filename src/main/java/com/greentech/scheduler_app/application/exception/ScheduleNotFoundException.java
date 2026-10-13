package com.greentech.scheduler_app.application.exception;

public class ScheduleNotFoundException extends RuntimeException {

	public ScheduleNotFoundException(String tenantId, String scheduleId) {
		super("Schedule not found: " + tenantId + "/" + scheduleId);
	}
}
