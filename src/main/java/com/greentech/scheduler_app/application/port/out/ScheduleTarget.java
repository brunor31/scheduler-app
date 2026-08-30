package com.greentech.scheduler_app.application.port.out;

import com.greentech.scheduler_app.domain.model.Schedule;

public interface ScheduleTarget {

	void dispatch(Schedule schedule);
}
