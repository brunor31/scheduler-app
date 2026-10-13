package com.greentech.scheduler_app.support;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.domain.model.*;
import java.time.Instant;
import java.util.*;
public abstract class StubScheduleRepository implements ScheduleRepository {
    public List<Schedule> findRecoverablePage(Instant horizon,RecoveryCursor after,int limit) { throw new UnsupportedOperationException(); }
    public boolean finishFailure(Schedule s,UUID token,DispatchResult result,Instant next) { throw new UnsupportedOperationException(); }
    public int expireExhausted(int limit) { throw new UnsupportedOperationException(); }
    public boolean redrive(Schedule s,long version) { throw new UnsupportedOperationException(); }
}
