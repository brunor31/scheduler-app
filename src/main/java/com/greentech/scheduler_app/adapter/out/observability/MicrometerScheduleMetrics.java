package com.greentech.scheduler_app.adapter.out.observability;

import com.greentech.scheduler_app.application.port.out.ScheduleMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MicrometerScheduleMetrics implements ScheduleMetrics {

	private final Counter deliveredCounter;
	private final Timer scheduleLag;
	private final Timer deliveryDuration;

	public MicrometerScheduleMetrics(MeterRegistry meterRegistry) {
		this.deliveredCounter = meterRegistry.counter("scheduler.schedules.delivered");
		this.scheduleLag = meterRegistry.timer("scheduler.schedule.lag");
		this.deliveryDuration = meterRegistry.timer("scheduler.delivery.duration");
	}

	@Override
	public void recordDelivered(Duration scheduleLag, Duration deliveryDuration) {
		deliveredCounter.increment();
		this.scheduleLag.record(scheduleLag);
		this.deliveryDuration.record(deliveryDuration);
	}
}
