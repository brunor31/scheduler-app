package com.greentech.scheduler_app.adapter.out.timer;

import com.greentech.scheduler_app.application.port.out.*;
import com.greentech.scheduler_app.domain.model.*;
import com.greentech.scheduler_app.infrastructure.config.TimerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ScheduleRecoveryLoader {
    private final ScheduleRepository repository;
    private final ScheduleTimer timer;
    private final Clock clock;
    private final TimerProperties properties;
    @org.springframework.beans.factory.annotation.Value("${scheduler.migration-only:false}")
    private boolean migrationOnly;
    private final AtomicBoolean loading=new AtomicBoolean();
    public ScheduleRecoveryLoader(ScheduleRepository repository,ScheduleTimer timer,Clock clock,Duration lookAhead,int batch) {
        this(repository,timer,clock,new TimerProperties(lookAhead,null,batch,100000,100,null));
    }
    @Autowired
    public ScheduleRecoveryLoader(ScheduleRepository repository,ScheduleTimer timer,Clock clock,TimerProperties properties) {
        this.repository=repository; this.timer=timer; this.clock=clock; this.properties=properties;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString="${scheduler.timer.reconciliation-interval:5s}",initialDelayString="${scheduler.timer.reconciliation-interval:5s}")
    public void loadUpcomingSchedules() {
        if(migrationOnly || !loading.compareAndSet(false,true)) return;
        try {
            repository.expireExhausted(properties.recoveryBatchSize());
            Instant horizon=clock.instant().plus(properties.lookAhead());
            RecoveryCursor cursor=null;
            for(int page=0;page<properties.maxPagesPerSweep() && timer.remainingCapacity()>0;page++) {
                var schedules=repository.findRecoverablePage(horizon,cursor,properties.recoveryBatchSize());
                if(schedules.isEmpty()) break;
                schedules.forEach(timer::schedule);
                cursor=RecoveryCursor.after(schedules.getLast());
                if(schedules.size()<properties.recoveryBatchSize()) break;
            }
        } finally { loading.set(false); }
    }
}
