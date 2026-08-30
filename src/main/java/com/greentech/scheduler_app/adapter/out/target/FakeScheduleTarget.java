package com.greentech.scheduler_app.adapter.out.target;

import com.greentech.scheduler_app.application.port.out.ScheduleTarget;
import com.greentech.scheduler_app.domain.model.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FakeScheduleTarget implements ScheduleTarget {

	private static final Logger LOGGER = LoggerFactory.getLogger(FakeScheduleTarget.class);

	@Override
	public void dispatch(Schedule schedule) {
		LOGGER.info(
				"Fake delivery completed for tenant={}, schedule={}, version={}",
				schedule.tenantId(),
				schedule.scheduleId(),
				schedule.version()
		);
	}
}
