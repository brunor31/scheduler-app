package com.greentech.scheduler_app.adapter.out.persistence;

import com.greentech.scheduler_app.application.exception.AdmissionLimitException;
import com.greentech.scheduler_app.application.exception.ScheduleAlreadyExistsException;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.application.port.out.ScheduleReadRepository;
import com.greentech.scheduler_app.domain.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

@Repository
public class JdbcScheduleRepository implements ScheduleRepository, ScheduleReadRepository {
    private static final String COLUMNS = """
        tenant_id,schedule_id,version,due_at,state,target_type,payload::text AS payload,
        created_at,updated_at,delivered_at,cancelled_at,target_id,max_attempts,initial_delay_ms,max_delay_ms,
        max_age_ms,attempts,next_attempt_at,last_error,max_lateness_ms,request_fingerprint
        """;
    private static final String EFFECTIVE = "COALESCE(next_attempt_at,due_at)";
    private final JdbcClient jdbc;
    private final int maxActive;
    public JdbcScheduleRepository(JdbcClient jdbc) { this(jdbc,100000); }
    @Autowired
    public JdbcScheduleRepository(JdbcClient jdbc, @Value("${scheduler.admission.max-active-per-tenant:100000}") int maxActive) {
        if(maxActive<1) throw new IllegalArgumentException("max active schedules must be positive");
        this.jdbc=jdbc; this.maxActive=maxActive;
    }
    @Override public void insert(Schedule s) {
        try {
            parameters(jdbc.sql("""
                WITH settings AS (SELECT set_config('scheduler.max_active_per_tenant', :maxActive, true))
                INSERT INTO schedule(tenant_id,schedule_id,version,due_at,state,target_type,payload,created_at,updated_at,
                    target_id,max_attempts,initial_delay_ms,max_delay_ms,max_age_ms,max_lateness_ms,request_fingerprint)
                SELECT :tenant,:id,:version,:due,:state,:type,CAST(:payload AS jsonb),:created,:updated,
                    :target,:maxAttempts,:initialDelay,:maxDelay,:maxAge,:lateness,:fingerprint FROM settings
                """),s).param("maxActive",Integer.toString(maxActive)).update();
        } catch(DuplicateKeyException e) { throw new ScheduleAlreadyExistsException(s.tenantId(),s.scheduleId()); }
        catch(DataAccessException e) { throw translateAdmission(e); }
    }
    @Override public Optional<Schedule> findById(String tenant,String id) {
        return jdbc.sql("SELECT "+COLUMNS+" FROM schedule WHERE tenant_id=:tenant AND schedule_id=:id")
                .param("tenant",tenant).param("id",id).query(this::map).optional();
    }
    @Override public List<Schedule> findRecoverableDueBefore(Instant horizon,int limit) {
        return findRecoverablePage(horizon,null,limit);
    }
    @Override public List<Schedule> findRecoverablePage(Instant horizon,RecoveryCursor after,int limit) {
        String sql="SELECT "+COLUMNS+" FROM schedule WHERE "+EFFECTIVE+"<=:horizon "
                +"AND (state IN ('SCHEDULED','RETRY_WAIT') OR (state='CLAIMED' AND lease_expires_at<=clock_timestamp())) "
                +(after==null?"":"AND ("+EFFECTIVE+",tenant_id,schedule_id)>(:afterDue,:afterTenant,:afterId) ")
                +"ORDER BY "+EFFECTIVE+",tenant_id,schedule_id LIMIT :limit";
        var q=jdbc.sql(sql).param("horizon",db(horizon)).param("limit",limit);
        if(after!=null) q=q.param("afterDue",db(after.effectiveDueAt())).param("afterTenant",after.tenantId()).param("afterId",after.scheduleId());
        return q.query(this::map).list();
    }
    @Override public boolean update(Schedule s,long expectedVersion) {
        return mutate(s,expectedVersion,"state IN ('SCHEDULED','RETRY_WAIT')");
    }
    @Override public boolean redrive(Schedule s,long expectedVersion) {
        return mutate(s,expectedVersion,"state IN ('DEAD_LETTERED','EXPIRED')");
    }
    private boolean mutate(Schedule s,long expectedVersion,String eligible) {
        try {
            return jdbc.sql("""
                WITH settings AS (SELECT set_config('scheduler.max_active_per_tenant', :maxActive, true))
                UPDATE schedule SET version=:version,due_at=:due,state=:state,updated_at=:updated,cancelled_at=:cancelled,
                    attempts=CASE WHEN :state='CANCELLED' THEN attempts ELSE 0 END,next_attempt_at=NULL,last_error=NULL,delivered_at=NULL
                FROM settings WHERE tenant_id=:tenant AND schedule_id=:id AND version=:expected AND
                """+eligible)
                .param("maxActive",Integer.toString(maxActive)).param("tenant",s.tenantId()).param("id",s.scheduleId())
                .param("version",s.version()).param("due",db(s.dueAt())).param("state",s.state().name())
                .param("updated",db(s.updatedAt())).param("cancelled",db(s.cancelledAt())).param("expected",expectedVersion).update()==1;
        } catch(DataAccessException e) { throw translateAdmission(e); }
    }
    @Override public Optional<Schedule> tryClaim(String tenant,String id,long version,UUID token,Duration lease) {
        Objects.requireNonNull(token);
        if(lease.toMillis()<1) throw new IllegalArgumentException("lease must be at least one millisecond");
        return jdbc.sql("""
                UPDATE schedule SET state='CLAIMED',attempt_token=:token,
                    lease_expires_at=clock_timestamp()+:lease*interval '1 millisecond',updated_at=clock_timestamp(),
                    attempts=attempts+1,last_error=NULL
                WHERE tenant_id=:tenant AND schedule_id=:id AND version=:version
                    AND COALESCE(next_attempt_at,due_at)<=clock_timestamp()
                    AND attempts<max_attempts AND due_at+max_age_ms*interval '1 millisecond'>clock_timestamp()
                    AND (attempts>0 OR max_lateness_ms IS NULL OR due_at+max_lateness_ms*interval '1 millisecond'>=clock_timestamp())
                    AND (state IN ('SCHEDULED','RETRY_WAIT') OR (state='CLAIMED' AND lease_expires_at<=clock_timestamp()))
                RETURNING %s
                """.formatted(COLUMNS))
                .param("tenant",tenant).param("id",id).param("version",version).param("token",token)
                .param("lease",lease.toMillis()).query(this::map).optional();
    }
    @Override public boolean markDelivered(String tenant,String id,long version,UUID token,Instant at) {
        return jdbc.sql("""
                UPDATE schedule SET state='DELIVERED',delivered_at=:at,updated_at=:at,attempt_token=NULL,
                    lease_expires_at=NULL,next_attempt_at=NULL,last_error=NULL
                WHERE tenant_id=:tenant AND schedule_id=:id AND version=:version AND state='CLAIMED'
                    AND attempt_token=:token AND lease_expires_at>clock_timestamp()
                """).param("tenant",tenant).param("id",id).param("version",version).param("token",token).param("at",db(at)).update()==1;
    }
    @Override public boolean finishFailure(Schedule s,UUID token,DispatchResult result,Instant nextAt) {
        boolean permanent=result.outcome()==DispatchResult.Outcome.PERMANENT;
        boolean deferred=result.outcome()==DispatchResult.Outcome.DEFERRED;
        return jdbc.sql("""
            UPDATE schedule SET
                state=CASE WHEN :permanent OR (NOT :deferred AND attempts>=max_attempts)
                    OR :next>due_at+max_age_ms*interval '1 millisecond' THEN 'DEAD_LETTERED' ELSE 'RETRY_WAIT' END,
                next_attempt_at=CASE WHEN :permanent OR (NOT :deferred AND attempts>=max_attempts)
                    OR :next>due_at+max_age_ms*interval '1 millisecond' THEN NULL ELSE :next END,
                attempts=CASE WHEN :deferred THEN greatest(0,attempts-1) ELSE attempts END,
                last_error=:code,attempt_token=NULL,lease_expires_at=NULL,updated_at=clock_timestamp()
            WHERE tenant_id=:tenant AND schedule_id=:id AND version=:version AND state='CLAIMED'
                AND attempt_token=:token AND lease_expires_at>clock_timestamp()
            """).param("permanent",permanent).param("deferred",deferred).param("next",db(nextAt)).param("code",result.code())
            .param("tenant",s.tenantId()).param("id",s.scheduleId()).param("version",s.version()).param("token",token).update()==1;
    }
    @Override public int expireExhausted(int limit) {
        return jdbc.sql("""
            WITH expired AS (
                SELECT tenant_id,schedule_id FROM schedule
                WHERE (state IN ('SCHEDULED','RETRY_WAIT') OR (state='CLAIMED' AND lease_expires_at<=clock_timestamp()))
                  AND (attempts>=max_attempts OR due_at+max_age_ms*interval '1 millisecond'<=clock_timestamp()
                    OR (attempts=0 AND max_lateness_ms IS NOT NULL AND due_at+max_lateness_ms*interval '1 millisecond'<clock_timestamp()))
                ORDER BY due_at LIMIT :limit FOR UPDATE SKIP LOCKED
            ) UPDATE schedule s SET
                state=CASE WHEN attempts=0 AND max_lateness_ms IS NOT NULL
                    AND due_at+max_lateness_ms*interval '1 millisecond'<clock_timestamp() THEN 'EXPIRED' ELSE 'DEAD_LETTERED' END,
                last_error=CASE WHEN attempts=0 AND max_lateness_ms IS NOT NULL
                    AND due_at+max_lateness_ms*interval '1 millisecond'<clock_timestamp() THEN 'MAX_LATENESS' ELSE 'RETRY_EXHAUSTED' END,
                attempt_token=NULL,lease_expires_at=NULL,next_attempt_at=NULL,updated_at=clock_timestamp()
            FROM expired e WHERE s.tenant_id=e.tenant_id AND s.schedule_id=e.schedule_id
            """).param("limit",limit).update();
    }
    @Override public List<Schedule> list(String tenant,ScheduleState state,Instant from,Instant to,String target,String after,int limit) {
        String sql="SELECT "+COLUMNS+" FROM schedule WHERE tenant_id=:tenant AND schedule_id>:after";
        if(state!=null) sql+=" AND state=:state";
        if(from!=null) sql+=" AND due_at>=:from";
        if(to!=null) sql+=" AND due_at<:to";
        if(target!=null) sql+=" AND target_id=:target";
        var q=jdbc.sql(sql+" ORDER BY schedule_id LIMIT :limit").param("tenant",tenant).param("after",after==null?"":after).param("limit",limit);
        if(state!=null) q=q.param("state",state.name());
        if(from!=null) q=q.param("from",db(from)); if(to!=null) q=q.param("to",db(to)); if(target!=null) q=q.param("target",target);
        return q.query(this::map).list();
    }
    @Override public List<ScheduleHistory> history(String tenant,String id,long after,int limit) {
        return jdbc.sql("SELECT * FROM schedule_history WHERE tenant_id=:tenant AND schedule_id=:id AND event_id>:after ORDER BY event_id LIMIT :limit")
            .param("tenant",tenant).param("id",id).param("after",after).param("limit",limit)
            .query((r,n)->new ScheduleHistory(r.getLong("event_id"),r.getLong("version"),r.getString("from_state"),r.getString("to_state"),
                r.getInt("attempt"),r.getString("reason"),instant(r,"observed_at"))).list();
    }
    private JdbcClient.StatementSpec parameters(JdbcClient.StatementSpec q,Schedule s) {
        return q.param("tenant",s.tenantId()).param("id",s.scheduleId()).param("version",s.version()).param("due",db(s.dueAt()))
                .param("state",s.state().name()).param("type",s.targetType()).param("payload",s.payload()).param("created",db(s.createdAt()))
                .param("updated",db(s.updatedAt())).param("target",s.targetId()).param("maxAttempts",s.retryPolicy().maxAttempts())
                .param("initialDelay",s.retryPolicy().initialDelay().toMillis()).param("maxDelay",s.retryPolicy().maxDelay().toMillis())
                .param("maxAge",s.retryPolicy().maxAge().toMillis()).param("lateness",s.maxLateness()==null?null:s.maxLateness().toMillis())
                .param("fingerprint",s.requestFingerprint());
    }
    private Schedule map(ResultSet r,int row) throws SQLException {
        Long lateness=r.getObject("max_lateness_ms",Long.class);
        return new Schedule(r.getString("tenant_id"),r.getString("schedule_id"),r.getLong("version"),instant(r,"due_at"),
            ScheduleState.valueOf(r.getString("state")),r.getString("target_type"),r.getString("payload"),instant(r,"created_at"),
            instant(r,"updated_at"),instant(r,"delivered_at"),instant(r,"cancelled_at"),r.getString("target_id"),
            new RetryPolicy(r.getInt("max_attempts"),Duration.ofMillis(r.getLong("initial_delay_ms")),
                Duration.ofMillis(r.getLong("max_delay_ms")),Duration.ofMillis(r.getLong("max_age_ms"))),
            r.getInt("attempts"),instant(r,"next_attempt_at"),r.getString("last_error"),lateness==null?null:Duration.ofMillis(lateness),r.getString("request_fingerprint"));
    }
    private static RuntimeException translateAdmission(DataAccessException e) {
        for(Throwable cause=e;cause!=null;cause=cause.getCause()) if(cause instanceof SQLException sql && "P0001".equals(sql.getSQLState())) return new AdmissionLimitException();
        return e;
    }
    private static OffsetDateTime db(Instant value) { return value==null?null:value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet r,String name) throws SQLException { OffsetDateTime t=r.getObject(name,OffsetDateTime.class); return t==null?null:t.toInstant(); }
}
