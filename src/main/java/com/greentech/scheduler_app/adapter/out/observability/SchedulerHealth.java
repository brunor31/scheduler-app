package com.greentech.scheduler_app.adapter.out.observability;
import com.greentech.scheduler_app.adapter.out.timer.LocalTimerScheduler;
import org.springframework.boot.health.contributor.*;
import org.springframework.stereotype.Component;
@Component("schedulerHealthIndicator")
public class SchedulerHealth implements HealthIndicator {
    private final LocalTimerScheduler timer;
    public SchedulerHealth(LocalTimerScheduler timer) { this.timer=timer; }
    @Override public Health health() { return timer.isRunning()?Health.up().build():Health.down().build(); }
}
