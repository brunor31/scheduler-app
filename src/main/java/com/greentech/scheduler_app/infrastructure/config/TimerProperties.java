package com.greentech.scheduler_app.infrastructure.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
@ConfigurationProperties("scheduler.timer")
public record TimerProperties(Duration lookAhead,Duration reconciliationInterval,int recoveryBatchSize,
        int maxLoaded,int maxPagesPerSweep,Duration drainTimeout) {
    public TimerProperties {
        lookAhead=lookAhead==null?Duration.ofMinutes(15):lookAhead;
        reconciliationInterval=reconciliationInterval==null?Duration.ofSeconds(5):reconciliationInterval;
        recoveryBatchSize=recoveryBatchSize==0?1000:recoveryBatchSize;
        maxLoaded=maxLoaded==0?100000:maxLoaded;
        maxPagesPerSweep=maxPagesPerSweep==0?100:maxPagesPerSweep;
        drainTimeout=drainTimeout==null?Duration.ofSeconds(25):drainTimeout;
        if(lookAhead.isNegative() || lookAhead.isZero() || reconciliationInterval.isNegative() || reconciliationInterval.isZero()
                || recoveryBatchSize<1 || maxLoaded<1 || maxPagesPerSweep<1 || drainTimeout.isNegative())
            throw new IllegalArgumentException("invalid timer configuration");
    }
}
