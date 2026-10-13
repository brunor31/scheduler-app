package com.greentech.scheduler_app.application.port.in;

import com.greentech.scheduler_app.domain.model.Schedule;

public interface RescheduleScheduleUseCase {

	Schedule reschedule(RescheduleScheduleCommand command);
}
