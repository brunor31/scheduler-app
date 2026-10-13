package com.greentech.scheduler_app.application.port.out;

import com.greentech.scheduler_app.domain.model.Schedule;

public interface ScheduleTimer {

    default int remainingCapacity() { return Integer.MAX_VALUE; }

	void schedule(Schedule schedule);
}
