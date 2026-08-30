package com.greentech.scheduler_app.application.exception;

public class ScheduleAlreadyExistsException extends RuntimeException {

	public ScheduleAlreadyExistsException(String tenantId, String scheduleId) {
		super("Schedule already exists: " + tenantId + "/" + scheduleId);
	}
}
