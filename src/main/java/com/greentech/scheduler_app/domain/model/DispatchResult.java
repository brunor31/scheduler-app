package com.greentech.scheduler_app.domain.model;

import java.time.Duration;
import java.util.Objects;

public record DispatchResult(Outcome outcome, String code, Duration retryAfter) {
    public enum Outcome { SUCCESS, RETRYABLE, PERMANENT, UNKNOWN, DEFERRED }
    public DispatchResult {
        Objects.requireNonNull(outcome);
        if (code == null || !code.matches("[A-Z0-9_]{1,80}")) throw new IllegalArgumentException("invalid outcome code");
        if (retryAfter != null && (retryAfter.isNegative() || retryAfter.compareTo(Duration.ofDays(1)) > 0)) {
            throw new IllegalArgumentException("invalid retryAfter");
        }
    }
    public static DispatchResult success() { return new DispatchResult(Outcome.SUCCESS, "SUCCESS", null); }
    public static DispatchResult of(Outcome outcome, String code) { return new DispatchResult(outcome, code, null); }
}
