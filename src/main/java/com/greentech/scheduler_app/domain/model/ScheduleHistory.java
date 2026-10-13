package com.greentech.scheduler_app.domain.model;
import java.time.Instant;
public record ScheduleHistory(long eventId, long version, String fromState, String toState,
        int attempt, String reason, Instant observedAt) {}
