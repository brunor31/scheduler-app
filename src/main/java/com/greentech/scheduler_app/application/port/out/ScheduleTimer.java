package com.greentech.scheduler_app.application.port.out;

import com.greentech.scheduler_app.domain.model.Schedule;

public interface ScheduleTimer {

	void schedule(Schedule schedule);
}
