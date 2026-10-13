package com.greentech.scheduler_app.application.port.out;

import com.greentech.scheduler_app.domain.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleRepository {

	void insert(Schedule schedule);

	Optional<Schedule> findById(String tenantId, String scheduleId);

	List<Schedule> findRecoverableDueBefore(Instant horizon, int limit);

    List<Schedule> findRecoverablePage(Instant horizon, RecoveryCursor after, int limit);

    boolean finishFailure(Schedule schedule, UUID attemptToken, DispatchResult result, Instant nextAttemptAt);

    int expireExhausted(int limit);

    boolean redrive(Schedule schedule, long expectedVersion);

	boolean update(Schedule schedule, long expectedVersion);

	/** Atomically acquires a due schedule or replaces an expired claim using database time. */
	Optional<Schedule> tryClaim(String tenantId, String scheduleId, long expectedVersion,
			UUID attemptToken, Duration leaseDuration);

	boolean markDelivered(String tenantId, String scheduleId, long expectedVersion,
			UUID attemptToken, Instant deliveredAt);
}
