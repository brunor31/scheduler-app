package com.greentech.scheduler_app.application.port.in;
import com.greentech.scheduler_app.domain.model.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
public record CreateScheduleCommand(String tenantId, String scheduleId, Instant dueAt, String payload,
        String targetId, RetryPolicy retryPolicy, Duration maxLateness) {
    public CreateScheduleCommand(String tenantId, String scheduleId, Instant dueAt, String payload) {
        this(tenantId,scheduleId,dueAt,payload,"fake",RetryPolicy.DEFAULT,null);
    }
}
