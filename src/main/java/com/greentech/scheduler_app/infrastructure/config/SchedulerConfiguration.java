package com.greentech.scheduler_app.infrastructure.config;

import com.greentech.scheduler_app.application.port.in.CancelScheduleUseCase;
import com.greentech.scheduler_app.application.port.in.CreateScheduleUseCase;
import com.greentech.scheduler_app.application.port.in.HandleDueScheduleUseCase;
import com.greentech.scheduler_app.application.port.in.RescheduleScheduleUseCase;
import com.greentech.scheduler_app.application.port.out.ScheduleMetrics;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleTarget;
import com.greentech.scheduler_app.application.port.out.ScheduleTimer;
import com.greentech.scheduler_app.application.service.CancelScheduleService;
import com.greentech.scheduler_app.application.service.CreateScheduleService;
import com.greentech.scheduler_app.application.service.HandleDueScheduleService;
import com.greentech.scheduler_app.application.service.RescheduleScheduleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.greentech.scheduler_app.application.port.out.TargetCatalog;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({TargetProperties.class, TimerProperties.class})
public class SchedulerConfiguration {
    @Bean
    com.greentech.scheduler_app.application.service.ScheduleQueryService queryService(
            ScheduleRepository repository, com.greentech.scheduler_app.application.port.out.ScheduleReadRepository reads,
            ScheduleTimer timer,Clock clock) {
        return new com.greentech.scheduler_app.application.service.ScheduleQueryService(repository,reads,timer,clock);
    }


	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	CreateScheduleUseCase createScheduleUseCase(
			ScheduleRepository repository,
			ScheduleTimer timer,
			Clock clock,
            TargetCatalog targets
	) {
		return new CreateScheduleService(repository, timer, clock, targets);
	}

	@Bean
	RescheduleScheduleUseCase rescheduleScheduleUseCase(
			ScheduleRepository repository,
			ScheduleTimer timer,
			Clock clock
	) {
		return new RescheduleScheduleService(repository, timer, clock);
	}

	@Bean
	CancelScheduleUseCase cancelScheduleUseCase(ScheduleRepository repository, Clock clock) {
		return new CancelScheduleService(repository, clock);
	}

	@Bean
	HandleDueScheduleUseCase handleDueScheduleUseCase(
			ScheduleRepository repository,
			ScheduleTarget target,
			ScheduleMetrics metrics,
			Clock clock,
			@Value("${scheduler.delivery.claim-lease:30s}") Duration claimLease, TargetProperties targets
	) {
		for (var endpoint : targets.endpoints().values()) {
            if (claimLease.compareTo(endpoint.timeout().plusSeconds(10)) < 0)
                throw new IllegalArgumentException("claim lease must exceed target timeout by at least 10 seconds");
        }
        return new HandleDueScheduleService(repository, target, metrics, clock, claimLease);
	}
}
