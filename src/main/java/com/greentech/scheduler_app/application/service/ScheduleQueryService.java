package com.greentech.scheduler_app.application.service;
import com.greentech.scheduler_app.application.exception.*;
import com.greentech.scheduler_app.application.port.out.*;
import com.greentech.scheduler_app.domain.exception.InvalidScheduleException;
import com.greentech.scheduler_app.domain.model.*;
import java.time.*;
import java.util.List;

public final class ScheduleQueryService {
    private final ScheduleRepository repository;
    private final ScheduleReadRepository reads;
    private final ScheduleTimer timer;
    private final Clock clock;
    public ScheduleQueryService(ScheduleRepository repository,ScheduleReadRepository reads,ScheduleTimer timer,Clock clock) {
        this.repository=repository; this.reads=reads; this.timer=timer; this.clock=clock;
    }
    public Schedule get(String tenant,String id) {
        Schedule.requireId(tenant,"tenantId"); Schedule.requireId(id,"scheduleId");
        return repository.findById(tenant,id).orElseThrow(()->new ScheduleNotFoundException(tenant,id));
    }
    public Page<Schedule> list(String tenant,ScheduleState state,Instant from,Instant to,String target,String cursor,int limit) {
        Schedule.requireId(tenant,"tenantId"); checkLimit(limit);
        if(cursor!=null) Schedule.requireId(cursor,"cursor");
        if(target!=null) Schedule.requireId(target,"targetId");
        if(from!=null && to!=null && !from.isBefore(to)) throw new InvalidScheduleException("dueFrom must precede dueTo");
        var rows=reads.list(tenant,state,from,to,target,cursor,limit+1);
        return new Page<>(rows.subList(0,Math.min(rows.size(),limit)),rows.size()>limit?rows.get(limit-1).scheduleId():null);
    }
    public Page<ScheduleHistory> history(String tenant,String id,long cursor,int limit) {
        get(tenant,id); checkLimit(limit);
        if(cursor<0) throw new InvalidScheduleException("invalid history cursor");
        var rows=reads.history(tenant,id,cursor,limit+1);
        return new Page<>(rows.subList(0,Math.min(rows.size(),limit)),rows.size()>limit?Long.toString(rows.get(limit-1).eventId()):null);
    }
    public Schedule redrive(String tenant,String id,long version,Instant dueAt) {
        Schedule current=get(tenant,id);
        if(current.version()!=version) throw new ScheduleVersionConflictException(tenant,id,version);
        if(current.state()!=ScheduleState.DEAD_LETTERED && current.state()!=ScheduleState.EXPIRED)
            throw new ScheduleStateConflictException(tenant,id,current.state());
        Schedule updated=current.redrive(dueAt,clock.instant());
        if(!repository.redrive(updated,version)) throw new ScheduleVersionConflictException(tenant,id,version);
        timer.schedule(updated);
        return updated;
    }
    private static void checkLimit(int limit) { if(limit<1 || limit>200) throw new InvalidScheduleException("limit must be 1..200"); }
    public record Page<T>(List<T> items,String nextCursor) {}
}
