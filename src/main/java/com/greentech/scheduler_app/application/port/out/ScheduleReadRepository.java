package com.greentech.scheduler_app.application.port.out;
import com.greentech.scheduler_app.domain.model.*;
import java.time.Instant;
import java.util.List;
public interface ScheduleReadRepository {
    List<Schedule> list(String tenantId, ScheduleState state, Instant dueFrom, Instant dueTo, String targetId, String afterId, int limit);
    List<ScheduleHistory> history(String tenantId, String scheduleId, long afterEventId, int limit);
}
