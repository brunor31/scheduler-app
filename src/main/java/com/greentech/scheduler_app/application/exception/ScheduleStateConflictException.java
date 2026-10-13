package com.greentech.scheduler_app.application.exception;

import com.greentech.scheduler_app.domain.model.ScheduleState;

public class ScheduleStateConflictException extends RuntimeException {

	public ScheduleStateConflictException(String tenantId, String scheduleId, ScheduleState state) {
		super("Schedule state conflict: " + tenantId + "/" + scheduleId + " is " + state);
	}
}
