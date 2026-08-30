package com.greentech.scheduler_app.application.port.out;

import java.time.Duration;

public interface ScheduleMetrics {

	void recordDelivered(Duration scheduleLag, Duration deliveryDuration);
}
