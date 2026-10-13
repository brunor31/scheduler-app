package com.greentech.scheduler_app.adapter.out.observability;
import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
@Component
public class BacklogMetrics {
    private final JdbcClient jdbc;
    private final Map<String,Double> values=new ConcurrentHashMap<>();
    public BacklogMetrics(JdbcClient jdbc,MeterRegistry registry) {
        this.jdbc=jdbc;
        for(String state:new String[]{"SCHEDULED","RETRY_WAIT","CLAIMED","DEAD_LETTERED","EXPIRED"})
            Gauge.builder("scheduler.backlog",values,v->v.getOrDefault(state,0d)).tag("state",state).register(registry);
        Gauge.builder("scheduler.overdue.oldest.seconds",values,v->v.getOrDefault("oldest",0d)).register(registry);
        Gauge.builder("scheduler.backlog.sample.success",values,v->v.getOrDefault("sample",0d)).register(registry);
    }
    @Scheduled(fixedDelayString="${scheduler.metrics.backlog-interval:30s}",initialDelayString="${scheduler.metrics.backlog-interval:30s}")
    public void sample() {
        try {
            Map<String,Double> next=new java.util.HashMap<>();
            jdbc.sql("""
                SELECT state,count(*) AS total FROM schedule
                WHERE state IN ('SCHEDULED','RETRY_WAIT','CLAIMED','DEAD_LETTERED','EXPIRED') GROUP BY state
                """).query((r,n)->Map.entry(r.getString("state"),r.getDouble("total"))).list().forEach(e->next.put(e.getKey(),e.getValue()));
            double oldest=jdbc.sql("""
                SELECT COALESCE(greatest(0,extract(epoch FROM clock_timestamp()-min(COALESCE(next_attempt_at,due_at)))),0)
                FROM schedule WHERE state IN ('SCHEDULED','RETRY_WAIT')
                """).query(Double.class).single();
            values.clear(); values.putAll(next); values.put("oldest",oldest); values.put("sample",1d);
        } catch(RuntimeException exception) { values.put("sample",0d); }
    }
}
