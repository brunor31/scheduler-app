package com.greentech.scheduler_app.application.service;

import com.greentech.scheduler_app.application.port.in.CreateScheduleCommand;
import com.greentech.scheduler_app.application.port.in.CreateScheduleUseCase;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleTimer;
import com.greentech.scheduler_app.domain.model.Schedule;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class CreateScheduleService implements CreateScheduleUseCase {

	private final ScheduleRepository repository;
	private final ScheduleTimer timer;
	private final Clock clock;

	public CreateScheduleService(ScheduleRepository repository, ScheduleTimer timer, Clock clock) {
		this.repository = Objects.requireNonNull(repository);
		this.timer = Objects.requireNonNull(timer);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	public Schedule create(CreateScheduleCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		Instant now = clock.instant();
		Schedule schedule = Schedule.create(
				command.tenantId(),
				command.scheduleId(),
				command.dueAt(),
				command.payload(),
				now
		);
		repository.insert(schedule);
		timer.schedule(schedule);
		return schedule;
	}
}
