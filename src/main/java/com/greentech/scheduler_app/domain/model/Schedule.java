package com.greentech.scheduler_app.domain.model;

import com.greentech.scheduler_app.domain.exception.InvalidScheduleException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

public record Schedule(
        String tenantId, String scheduleId, long version, Instant dueAt, ScheduleState state,
        String targetType, String payload, Instant createdAt, Instant updatedAt, Instant deliveredAt, Instant cancelledAt,
        String targetId, RetryPolicy retryPolicy, int attempts, Instant nextAttemptAt, String lastError,
        Duration maxLateness, String requestFingerprint
) {
    public Schedule {
        requireId(tenantId, "tenantId");
        requireId(scheduleId, "scheduleId");
        requireId(targetId, "targetId");
        if (version < 1 || attempts < 0) throw new InvalidScheduleException("invalid version or attempts");
        Objects.requireNonNull(dueAt); Objects.requireNonNull(state); Objects.requireNonNull(payload);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt); Objects.requireNonNull(retryPolicy);
        if (!"FAKE".equals(targetType) && !"HTTP".equals(targetType)) throw new InvalidScheduleException("unsupported target type");
        if (maxLateness != null && (maxLateness.isNegative() || maxLateness.isZero() || maxLateness.compareTo(Duration.ofDays(30)) > 0)) {
            throw new InvalidScheduleException("maxLateness must be greater than zero and at most 30 days");
        }
    }

    // Source-compatible constructor for the original POC fixtures and historical rows.
    public Schedule(String tenantId, String scheduleId, long version, Instant dueAt, ScheduleState state,
            String targetType, String payload, Instant createdAt, Instant updatedAt, Instant deliveredAt, Instant cancelledAt) {
        this(tenantId, scheduleId, version, dueAt, state, targetType, payload, createdAt, updatedAt, deliveredAt,
                cancelledAt, "fake", RetryPolicy.DEFAULT, 0, null, null, null, null);
    }

    public static Schedule create(String tenantId, String scheduleId, Instant dueAt, String payload, Instant now) {
        return create(tenantId, scheduleId, dueAt, payload, now, "fake", "FAKE", RetryPolicy.DEFAULT, null, null);
    }

    public static Schedule create(String tenantId, String scheduleId, Instant dueAt, String payload, Instant now,
            String targetId, String targetType, RetryPolicy policy, Duration maxLateness, String fingerprint) {
        if (dueAt == null || !dueAt.isAfter(now)) throw new InvalidScheduleException("dueAt must be in the future");
        if (dueAt.isAfter(now.plus(Duration.ofDays(366)))) throw new InvalidScheduleException("maximum scheduling horizon is 366 days");
        return new Schedule(tenantId, scheduleId, 1, dueAt, ScheduleState.SCHEDULED, targetType,
                payload == null ? "{}" : payload, now, now, null, null, targetId, policy, 0, null, null, maxLateness, fingerprint);
    }

    public Schedule reschedule(Instant newDueAt, Instant now) {
        requireMutable("reschedule");
        if (newDueAt == null || !newDueAt.isAfter(now) || newDueAt.isAfter(now.plus(Duration.ofDays(366)))) {
            throw new InvalidScheduleException("dueAt must be future and within 366 days");
        }
        return copy(nextVersion(), newDueAt, ScheduleState.SCHEDULED, now, null);
    }

    public Schedule cancel(Instant now) {
        requireMutable("cancel");
        return copy(nextVersion(), dueAt, ScheduleState.CANCELLED, now, now);
    }

    public Schedule redrive(Instant newDueAt, Instant now) {
        if (state != ScheduleState.DEAD_LETTERED && state != ScheduleState.EXPIRED) throw new InvalidScheduleException("only DLQ or expired schedules can be redriven");
        if (newDueAt == null || !newDueAt.isAfter(now) || newDueAt.isAfter(now.plus(Duration.ofDays(366)))) throw new InvalidScheduleException("invalid redrive deadline");
        return copy(nextVersion(), newDueAt, ScheduleState.SCHEDULED, now, null);
    }

    private Schedule copy(long newVersion, Instant due, ScheduleState newState, Instant now, Instant cancelled) {
        return new Schedule(tenantId, scheduleId, newVersion, due, newState, targetType, payload, createdAt, now,
                null, cancelled, targetId, retryPolicy, newState == ScheduleState.CANCELLED ? attempts : 0, null, null, maxLateness, requestFingerprint);
    }

    public Instant effectiveDueAt() { return nextAttemptAt == null ? dueAt : nextAttemptAt; }

    public String idempotencyKey() {
        // Length-delimited identity avoids separator collisions and keeps headers bounded.
        return sha256(tenantId.length() + ":" + tenantId + scheduleId.length() + ":" + scheduleId + ":" + version);
    }

    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private long nextVersion() {
        try { return Math.incrementExact(version); }
        catch (ArithmeticException exception) { throw new InvalidScheduleException("version cannot be incremented"); }
    }
    private void requireMutable(String operation) {
        if (!state.mutable()) throw new InvalidScheduleException("cannot " + operation + " schedule in state " + state);
    }
    public static void requireId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new InvalidScheduleException(field + " must be 1..128 ASCII letters, digits, dots, underscores or hyphens");
    }
}
