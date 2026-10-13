package com.greentech.scheduler_app.adapter.out.observability;
import com.greentech.scheduler_app.application.port.out.ScheduleMetrics;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
@Component
public class MicrometerScheduleMetrics implements ScheduleMetrics {
    private final MeterRegistry registry;
    private final Counter delivered;
    private final Timer lag,delivery,claim,dispatch;
    public MicrometerScheduleMetrics(MeterRegistry registry) {
        this.registry=registry;
        delivered=registry.counter("scheduler.schedules.delivered");
        lag=timer(registry,"scheduler.schedule.lag"); delivery=timer(registry,"scheduler.delivery.duration");
        claim=timer(registry,"scheduler.claim.lag"); dispatch=timer(registry,"scheduler.dispatch.lag");
    }
    private static Timer timer(MeterRegistry registry,String name) {
        return Timer.builder(name).publishPercentileHistogram().serviceLevelObjectives(Duration.ofMillis(100),Duration.ofMillis(500),
            Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(3),Duration.ofSeconds(10),Duration.ofSeconds(30)).register(registry);
    }
    @Override public void recordDelivered(Duration lagValue,Duration duration) { delivered.increment(); lag.record(lagValue); delivery.record(duration); recordAttempt("SUCCESS"); }
    @Override public void recordAttempt(String outcome) { registry.counter("scheduler.delivery.attempts","outcome",outcome).increment(); }
    @Override public void recordClaim(Duration delay) { claim.record(delay); }
    @Override public void recordDispatch(Duration delay) { dispatch.record(delay); }
}
