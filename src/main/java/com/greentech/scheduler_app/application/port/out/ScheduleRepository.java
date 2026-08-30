package com.greentech.scheduler_app.application.port.out;

import com.greentech.scheduler_app.domain.model.Schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository {

	void insert(Schedule schedule);

	Optional<Schedule> findById(String tenantId, String scheduleId);

	List<Schedule> findScheduledDueBefore(Instant horizon, int limit);

	boolean markDelivered(String tenantId, String scheduleId, long expectedVersion, Instant deliveredAt);
}
