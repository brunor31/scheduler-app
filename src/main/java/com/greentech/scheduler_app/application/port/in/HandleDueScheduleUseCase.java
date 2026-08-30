package com.greentech.scheduler_app.application.port.in;

public interface HandleDueScheduleUseCase {

	void handle(String tenantId, String scheduleId, long version);
}
