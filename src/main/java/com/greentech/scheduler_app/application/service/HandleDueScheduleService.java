package com.greentech.scheduler_app.application.service;

import com.greentech.scheduler_app.application.port.in.HandleDueScheduleUseCase;
import com.greentech.scheduler_app.application.port.out.ScheduleMetrics;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleTarget;
import com.greentech.scheduler_app.domain.model.Schedule;
import com.greentech.scheduler_app.domain.model.DispatchResult;
import com.greentech.scheduler_app.application.exception.DispatchFailureException;
import java.util.concurrent.ThreadLocalRandom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class HandleDueScheduleService implements HandleDueScheduleUseCase {

	private final ScheduleRepository repository;
	private final ScheduleTarget target;
	private final ScheduleMetrics metrics;
	private final Clock clock;
	private final Duration claimLease;

	public HandleDueScheduleService(
			ScheduleRepository repository,
			ScheduleTarget target,
			ScheduleMetrics metrics,
			Clock clock,
			Duration claimLease
	) {
		this.repository = Objects.requireNonNull(repository);
		this.target = Objects.requireNonNull(target);
		this.metrics = Objects.requireNonNull(metrics);
		this.clock = Objects.requireNonNull(clock);
		this.claimLease = Objects.requireNonNull(claimLease);
		if (claimLease.toMillis() < 1) {
			throw new IllegalArgumentException("claimLease must be at least one millisecond");
		}
	}

	@Override
	public void handle(String tenantId, String scheduleId, long version) {
		UUID attemptToken = UUID.randomUUID();
		Schedule schedule = repository.tryClaim(tenantId, scheduleId, version, attemptToken, claimLease).orElse(null);
		if (schedule == null) {
			return;
		}

		Instant dispatchStartedAt = clock.instant();
        metrics.recordClaim(nonNegative(Duration.between(schedule.effectiveDueAt(), schedule.updatedAt())));
        metrics.recordDispatch(nonNegative(Duration.between(schedule.effectiveDueAt(), dispatchStartedAt)));
        try {
            target.dispatch(schedule);
        } catch (RuntimeException exception) {
            DispatchResult result = exception instanceof DispatchFailureException failure ? failure.result()
                    : DispatchResult.of(DispatchResult.Outcome.UNKNOWN, "UNEXPECTED_TARGET_FAILURE");
            Duration delay = result.outcome() == DispatchResult.Outcome.DEFERRED ? Duration.ofMillis(250)
                    : schedule.retryPolicy().delayFor(schedule.attempts(), ThreadLocalRandom.current().nextDouble());
            if (result.retryAfter() != null && result.retryAfter().compareTo(delay) > 0) delay = result.retryAfter();
            boolean recorded = repository.finishFailure(schedule, attemptToken, result, clock.instant().plus(delay));
            metrics.recordAttempt(recorded ? result.outcome().name() : "STALE_COMPLETION");
            return;
        }
		Instant deliveredAt = clock.instant();
		if (repository.markDelivered(tenantId, scheduleId, version, attemptToken, deliveredAt)) {
			metrics.recordDelivered(
					nonNegative(Duration.between(schedule.dueAt(), dispatchStartedAt)),
					nonNegative(Duration.between(dispatchStartedAt, deliveredAt))
			);
		} else {
            metrics.recordAttempt("STALE_COMPLETION");
        }
	}

	private static Duration nonNegative(Duration duration) {
		return duration.isNegative() ? Duration.ZERO : duration;
	}
}
