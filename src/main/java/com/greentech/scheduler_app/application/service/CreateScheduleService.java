package com.greentech.scheduler_app.application.service;

import com.greentech.scheduler_app.application.port.in.*;
import com.greentech.scheduler_app.application.port.out.*;
import com.greentech.scheduler_app.application.exception.ScheduleAlreadyExistsException;
import com.greentech.scheduler_app.domain.model.*;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import com.greentech.scheduler_app.domain.exception.InvalidScheduleException;

public final class CreateScheduleService implements CreateScheduleUseCase {
    private final ScheduleRepository repository;
    private final ScheduleTimer timer;
    private final Clock clock;
    private final TargetCatalog targets;
    public CreateScheduleService(ScheduleRepository repository, ScheduleTimer timer, Clock clock) {
        this(repository,timer,clock,(tenant,id) -> "FAKE");
    }
    public CreateScheduleService(ScheduleRepository repository, ScheduleTimer timer, Clock clock, TargetCatalog targets) {
        this.repository=repository; this.timer=timer; this.clock=clock; this.targets=targets;
    }
    @Override public Schedule create(CreateScheduleCommand command) {
        Schedule.requireId(command.tenantId(),"tenantId"); Schedule.requireId(command.scheduleId(),"scheduleId");
        String targetId=command.targetId()==null?"fake":command.targetId();
        String type=targets.typeFor(command.tenantId(),targetId);
        String payload=command.payload()==null?"{}":command.payload();
        if(payload.getBytes(StandardCharsets.UTF_8).length>65536) throw new InvalidScheduleException("payload exceeds 64 KiB");
        RetryPolicy policy=command.retryPolicy()==null?RetryPolicy.DEFAULT:command.retryPolicy();
        String fingerprint=Schedule.sha256(command.dueAt()+"|"+targetId+"|"+type+"|"+policy+"|"+command.maxLateness()+"|"+payload);
        var existing=repository.findById(command.tenantId(),command.scheduleId());
        if(existing.isPresent()) return sameRequest(existing.get(),fingerprint);
        Schedule schedule=Schedule.create(command.tenantId(),command.scheduleId(),command.dueAt(),payload,clock.instant(),
                targetId,type,policy,command.maxLateness(),fingerprint);
        try { repository.insert(schedule); }
        catch(ScheduleAlreadyExistsException conflict) {
            return sameRequest(repository.findById(command.tenantId(),command.scheduleId()).orElseThrow(() -> conflict),fingerprint);
        }
        timer.schedule(schedule);
        return schedule;
    }
    private Schedule sameRequest(Schedule existing,String fingerprint) {
        if(!fingerprint.equals(existing.requestFingerprint())) throw new ScheduleAlreadyExistsException(existing.tenantId(),existing.scheduleId());
        return existing;
    }
}
