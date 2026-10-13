package com.greentech.scheduler_app.domain.model;

import com.greentech.scheduler_app.domain.exception.InvalidScheduleException;
import java.time.Duration;

public record RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay, Duration maxAge) {
    public static final RetryPolicy DEFAULT = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofDays(1));

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 100 || initialDelay == null || maxDelay == null || maxAge == null
                || initialDelay.compareTo(Duration.ofMillis(100)) < 0 || maxDelay.compareTo(initialDelay) < 0
                || maxDelay.compareTo(Duration.ofDays(1)) > 0 || maxAge.compareTo(Duration.ofSeconds(1)) < 0
                || maxAge.compareTo(Duration.ofDays(30)) > 0) {
            throw new InvalidScheduleException("invalid retry policy: attempts 1..100, delay 100ms..1d, age 1s..30d");
        }
    }

    public Duration delayFor(int attempt, double jitter) {
        long base = initialDelay.toMillis();
        for (int i = 1; i < attempt && base < maxDelay.toMillis(); i++) base = Math.min(maxDelay.toMillis(), base * 2);
        // Equal jitter avoids synchronized retries while retaining a nonzero lower bound.
        return Duration.ofMillis(Math.max(1, (long) (base * (0.5 + Math.max(0, Math.min(1, jitter)) * 0.5))));
    }
}
