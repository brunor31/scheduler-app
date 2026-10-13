package com.greentech.scheduler_app.domain.model;

public enum ScheduleState {
    SCHEDULED, CLAIMED, RETRY_WAIT, DELIVERED, CANCELLED, DEAD_LETTERED, EXPIRED;
    public boolean mutable() { return this == SCHEDULED || this == RETRY_WAIT; }
    public boolean terminal() { return this == DELIVERED || this == CANCELLED || this == DEAD_LETTERED || this == EXPIRED; }
}
