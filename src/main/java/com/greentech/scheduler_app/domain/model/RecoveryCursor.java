package com.greentech.scheduler_app.domain.model;
import java.time.Instant;
public record RecoveryCursor(Instant effectiveDueAt, String tenantId, String scheduleId) {
    public static RecoveryCursor after(Schedule schedule) {
        return new RecoveryCursor(schedule.effectiveDueAt(), schedule.tenantId(), schedule.scheduleId());
    }
}
