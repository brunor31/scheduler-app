package com.greentech.scheduler_app.application.service;

import com.greentech.scheduler_app.application.port.in.HandleDueScheduleUseCase;
import com.greentech.scheduler_app.application.port.out.ScheduleMetrics;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleTarget;
import com.greentech.scheduler_app.domain.model.Schedule;
import com.greentech.scheduler_app.domain.model.ScheduleState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class HandleDueScheduleService implements HandleDueScheduleUseCase {

	private final ScheduleRepository repository;
	private final ScheduleTarget target;
	private final ScheduleMetrics metrics;
	private final Clock clock;

	public HandleDueScheduleService(
			ScheduleRepository repository,
			ScheduleTarget target,
			ScheduleMetrics metrics,
			Clock clock
	) {
		this.repository = Objects.requireNonNull(repository);
		this.target = Objects.requireNonNull(target);
		this.metrics = Objects.requireNonNull(metrics);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	public void handle(String tenantId, String scheduleId, long version) {
		Schedule schedule = repository.findById(tenantId, scheduleId).orElse(null);
		if (schedule == null || schedule.state() != ScheduleState.SCHEDULED || schedule.version() != version) {
			return;
		}

		Instant dispatchStartedAt = clock.instant();
		target.dispatch(schedule);
		Instant deliveredAt = clock.instant();
		if (repository.markDelivered(tenantId, scheduleId, version, deliveredAt)) {
			metrics.recordDelivered(
					nonNegative(Duration.between(schedule.dueAt(), dispatchStartedAt)),
					nonNegative(Duration.between(dispatchStartedAt, deliveredAt))
			);
		}
	}

	private static Duration nonNegative(Duration duration) {
		return duration.isNegative() ? Duration.ZERO : duration;
	}
}
