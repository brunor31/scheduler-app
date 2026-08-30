package com.greentech.scheduler_app.infrastructure.config;

import com.greentech.scheduler_app.application.port.in.CreateScheduleUseCase;
import com.greentech.scheduler_app.application.port.in.HandleDueScheduleUseCase;
import com.greentech.scheduler_app.application.port.out.ScheduleMetrics;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleTarget;
import com.greentech.scheduler_app.application.port.out.ScheduleTimer;
import com.greentech.scheduler_app.application.service.CreateScheduleService;
import com.greentech.scheduler_app.application.service.HandleDueScheduleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulerConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	CreateScheduleUseCase createScheduleUseCase(
			ScheduleRepository repository,
			ScheduleTimer timer,
			Clock clock
	) {
		return new CreateScheduleService(repository, timer, clock);
	}

	@Bean
	HandleDueScheduleUseCase handleDueScheduleUseCase(
			ScheduleRepository repository,
			ScheduleTarget target,
			ScheduleMetrics metrics,
			Clock clock
	) {
		return new HandleDueScheduleService(repository, target, metrics, clock);
	}
}
