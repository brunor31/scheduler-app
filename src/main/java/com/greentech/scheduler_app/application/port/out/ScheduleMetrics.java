package com.greentech.scheduler_app.application.port.out;

import java.time.Duration;

public interface ScheduleMetrics {

    default void recordAttempt(String outcome) {}
    default void recordClaim(Duration delay) {}
    default void recordDispatch(Duration delay) {}

	void recordDelivered(Duration scheduleLag, Duration deliveryDuration);
}
