package com.greentech.scheduler_app.application.service;

import com.greentech.scheduler_app.application.exception.ScheduleNotFoundException;
import com.greentech.scheduler_app.application.exception.ScheduleStateConflictException;
import com.greentech.scheduler_app.application.exception.ScheduleVersionConflictException;
import com.greentech.scheduler_app.application.port.in.RescheduleScheduleCommand;
import com.greentech.scheduler_app.application.port.in.RescheduleScheduleUseCase;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleTimer;
import com.greentech.scheduler_app.domain.model.Schedule;
import com.greentech.scheduler_app.domain.model.ScheduleState;

import java.time.Clock;
import java.util.Objects;

public final class RescheduleScheduleService implements RescheduleScheduleUseCase {

	private final ScheduleRepository repository;
	private final ScheduleTimer timer;
	private final Clock clock;

	public RescheduleScheduleService(ScheduleRepository repository, ScheduleTimer timer, Clock clock) {
		this.repository = Objects.requireNonNull(repository);
		this.timer = Objects.requireNonNull(timer);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	public Schedule reschedule(RescheduleScheduleCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		Schedule current = repository.findById(command.tenantId(), command.scheduleId())
				.orElseThrow(() -> new ScheduleNotFoundException(command.tenantId(), command.scheduleId()));
		if (current.version() != command.expectedVersion()) {
			throw new ScheduleVersionConflictException(
					command.tenantId(), command.scheduleId(), command.expectedVersion());
		}
		if (!current.state().mutable()) {
			throw new ScheduleStateConflictException(command.tenantId(), command.scheduleId(), current.state());
		}

		Schedule rescheduled = current.reschedule(command.dueAt(), clock.instant());
		if (!repository.update(rescheduled, command.expectedVersion())) {
			Schedule latest = repository.findById(command.tenantId(), command.scheduleId())
					.orElseThrow(() -> new ScheduleNotFoundException(command.tenantId(), command.scheduleId()));
			if (latest.version() == command.expectedVersion() && !latest.state().mutable()) {
				throw new ScheduleStateConflictException(command.tenantId(), command.scheduleId(), latest.state());
			}
			throw new ScheduleVersionConflictException(
					command.tenantId(), command.scheduleId(), command.expectedVersion());
		}
		timer.schedule(rescheduled);
		return rescheduled;
	}
}
