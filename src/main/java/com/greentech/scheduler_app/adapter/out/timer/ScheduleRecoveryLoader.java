package com.greentech.scheduler_app.adapter.out.timer;

import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleTimer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
public class ScheduleRecoveryLoader {

	private final ScheduleRepository repository;
	private final ScheduleTimer timer;
	private final Clock clock;
	private final Duration lookAhead;
	private final int batchSize;

	public ScheduleRecoveryLoader(
			ScheduleRepository repository,
			ScheduleTimer timer,
			Clock clock,
			@Value("${scheduler.timer.look-ahead:15m}") Duration lookAhead,
			@Value("${scheduler.timer.recovery-batch-size:10000}") int batchSize
	) {
		this.repository = repository;
		this.timer = timer;
		this.clock = clock;
		this.lookAhead = lookAhead;
		this.batchSize = batchSize;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(
			fixedDelayString = "${scheduler.timer.reconciliation-interval:60s}",
			initialDelayString = "${scheduler.timer.reconciliation-interval:60s}"
	)
	public void loadUpcomingSchedules() {
		repository.findScheduledDueBefore(clock.instant().plus(lookAhead), batchSize).forEach(timer::schedule);
	}
}
