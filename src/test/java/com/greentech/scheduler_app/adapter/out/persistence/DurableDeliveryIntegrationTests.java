package com.greentech.scheduler_app.adapter.out.persistence;

import com.greentech.scheduler_app.application.exception.*;
import com.greentech.scheduler_app.application.port.in.CreateScheduleCommand;
import com.greentech.scheduler_app.application.service.*;
import com.greentech.scheduler_app.domain.model.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DurableDeliveryIntegrationTests {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    static JdbcClient jdbc;
    JdbcScheduleRepository repository;
    @BeforeAll static void migrate() {
        var source=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(source).load().migrate(); jdbc=JdbcClient.create(source);
    }
    @BeforeEach void reset() { jdbc.sql("TRUNCATE schedule,schedule_history").update(); repository=new JdbcScheduleRepository(jdbc); }
    Schedule insert(String id,RetryPolicy policy,Duration lateness) {
        Instant now=Instant.now();
        Schedule s=Schedule.create("tenant-a",id,now.minusSeconds(1),"{}",now.minusSeconds(2),"fake","FAKE",policy,lateness,null);
        repository.insert(s); return s;
    }
    Schedule get(String id) { return repository.findById("tenant-a",id).orElseThrow(); }
    Schedule claim(String id,UUID token) { return repository.tryClaim("tenant-a",id,1,token,Duration.ofSeconds(30)).orElseThrow(); }
    void retryDue() { jdbc.sql("UPDATE schedule SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE state='RETRY_WAIT'").update(); }
    @Test void persistsRetryDeadlineAndStableLogicalKeyThenCompletes() {
        Schedule first=insert("retry",RetryPolicy.DEFAULT,null);
        UUID token=UUID.randomUUID(); Schedule claimed=claim("retry",token);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(repository.finishFailure(claimed,token,DispatchResult.of(DispatchResult.Outcome.RETRYABLE,"HTTP_503"),Instant.now().plusSeconds(60))).isTrue();
        assertThat(get("retry").state()).isEqualTo(ScheduleState.RETRY_WAIT);
        assertThat(repository.tryClaim("tenant-a","retry",1,UUID.randomUUID(),Duration.ofSeconds(30))).isEmpty();
        retryDue(); UUID second=UUID.randomUUID(); Schedule retry=claim("retry",second);
        assertThat(retry.attempts()).isEqualTo(2); assertThat(retry.idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(repository.markDelivered("tenant-a","retry",1,token,Instant.now())).isFalse();
        assertThat(repository.markDelivered("tenant-a","retry",1,second,Instant.now())).isTrue();
        assertThat(repository.history("tenant-a","retry",0,20)).extracting(ScheduleHistory::toState)
                .containsExactly("SCHEDULED","CLAIMED","RETRY_WAIT","RETRY_WAIT","CLAIMED","DELIVERED");
    }
    @Test void attemptBudgetAndPermanentFailureLeadToDlq() {
        RetryPolicy once=new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofHours(1));
        insert("budget",once,null); UUID token=UUID.randomUUID(); Schedule s=claim("budget",token);
        repository.finishFailure(s,token,DispatchResult.of(DispatchResult.Outcome.UNKNOWN,"HTTP_TIMEOUT"),Instant.now().plusSeconds(1));
        assertThat(get("budget").state()).isEqualTo(ScheduleState.DEAD_LETTERED);
        insert("permanent",RetryPolicy.DEFAULT,null); token=UUID.randomUUID(); s=claim("permanent",token);
        repository.finishFailure(s,token,DispatchResult.of(DispatchResult.Outcome.PERMANENT,"HTTP_400"),Instant.now().plusSeconds(1));
        assertThat(get("permanent").state()).isEqualTo(ScheduleState.DEAD_LETTERED);
    }
    @Test void expiredLastClaimDoesNotRetryBeyondBudget() {
        insert("lost",new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofHours(1)),null);
        claim("lost",UUID.randomUUID());
        jdbc.sql("UPDATE schedule SET lease_expires_at=clock_timestamp()-interval '1 second'").update();
        assertThat(repository.expireExhausted(10)).isEqualTo(1);
        assertThat(get("lost").state()).isEqualTo(ScheduleState.DEAD_LETTERED);
        assertThat(get("lost").lastError()).isEqualTo("RETRY_EXHAUSTED");
    }
    @Test void misfireExpiresWithoutCallingTarget() {
        insert("late",RetryPolicy.DEFAULT,Duration.ofMillis(100));
        assertThat(repository.tryClaim("tenant-a","late",1,UUID.randomUUID(),Duration.ofSeconds(30))).isEmpty();
        repository.expireExhausted(10);
        assertThat(get("late").state()).isEqualTo(ScheduleState.EXPIRED);
        assertThat(get("late").attempts()).isZero();
    }
    @Test void retryAgeBudgetStopsOldWork() {
        insert("old",new RetryPolicy(5,Duration.ofMillis(100),Duration.ofSeconds(1),Duration.ofSeconds(1)),null);
        jdbc.sql("UPDATE schedule SET due_at=clock_timestamp()-interval '10 seconds'").update();
        repository.expireExhausted(10);
        assertThat(get("old").state()).isEqualTo(ScheduleState.DEAD_LETTERED);
    }
    @Test void redriveCreatesNewIntentAndRejectsStaleVersion() {
        insert("redrive",RetryPolicy.DEFAULT,Duration.ofMillis(100)); repository.expireExhausted(10);
        Schedule before=get("redrive");
        var service=new ScheduleQueryService(repository,repository,s->{},Clock.systemUTC());
        Schedule next=service.redrive("tenant-a","redrive",1,Instant.now().plusSeconds(60));
        assertThat(next.version()).isEqualTo(2); assertThat(next.attempts()).isZero();
        assertThat(next.idempotencyKey()).isNotEqualTo(before.idempotencyKey());
        assertThatThrownBy(()->service.redrive("tenant-a","redrive",1,Instant.now().plusSeconds(60)))
                .isInstanceOf(ScheduleVersionConflictException.class);
    }
    @Test void createReplaySurvivesMutationAndConflictingBodyIsRejected() {
        var service=new CreateScheduleService(repository,s->{},Clock.systemUTC());
        var command=new CreateScheduleCommand("tenant-a","create",Instant.now().plusSeconds(60),"{}");
        Schedule created=service.create(command);
        repository.update(created.cancel(Instant.now()),1);
        assertThat(service.create(command).state()).isEqualTo(ScheduleState.CANCELLED);
        assertThat(repository.history("tenant-a","create",0,20)).hasSize(2);
        assertThatThrownBy(()->service.create(new CreateScheduleCommand("tenant-a","create",command.dueAt(),"{\"changed\":true}")))
                .isInstanceOf(ScheduleAlreadyExistsException.class);
    }
    @Test void tenantQuotaIsAtomicAndTerminalRowsReleaseCapacity() {
        repository=new JdbcScheduleRepository(jdbc,1);
        Schedule a=insert("a",RetryPolicy.DEFAULT,null);
        assertThatThrownBy(()->insert("b",RetryPolicy.DEFAULT,null)).isInstanceOf(AdmissionLimitException.class);
        assertThat(repository.history("tenant-a","b",0,20)).isEmpty();
        repository.update(a.cancel(Instant.now()),1);
        insert("b",RetryPolicy.DEFAULT,null);
    }
    @Test void recoveryKeysetAndTenantScopedQueriesDoNotSkipSameDeadline() {
        Instant now=Instant.now(), due=now.plusSeconds(60);
        for(String id:List.of("a","b","c")) repository.insert(Schedule.create("tenant-a",id,due,"{}",now));
        var first=repository.findRecoverablePage(now.plusSeconds(900),null,2);
        var second=repository.findRecoverablePage(now.plusSeconds(900),RecoveryCursor.after(first.getLast()),2);
        assertThat(first).extracting(Schedule::scheduleId).containsExactly("a","b");
        assertThat(second).extracting(Schedule::scheduleId).containsExactly("c");
        assertThat(repository.list("tenant-other",null,null,null,null,null,10)).isEmpty();
        assertThat(repository.history("tenant-other","a",0,10)).isEmpty();
    }
    @Test void localCapacityDeferralDoesNotConsumeAttemptBudget() {
        insert("busy",RetryPolicy.DEFAULT,null); UUID token=UUID.randomUUID(); Schedule s=claim("busy",token);
        repository.finishFailure(s,token,DispatchResult.of(DispatchResult.Outcome.DEFERRED,"TARGET_BUSY"),Instant.now().plusSeconds(1));
        assertThat(get("busy").attempts()).isZero(); assertThat(get("busy").state()).isEqualTo(ScheduleState.RETRY_WAIT);
    }

    @Test void concurrentAdmissionsAcrossConnectionsCannotExceedTenantQuota() throws Exception {
        repository=new JdbcScheduleRepository(jdbc,3);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(12)) {
            var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<12;i++) {
                String id="parallel-"+i;
                futures.add(executor.submit(()->{start.await(); try { insert(id,RetryPolicy.DEFAULT,null); return true; }
                    catch(AdmissionLimitException expected) { return false; }}));
            }
            start.countDown(); int accepted=0;
            for(var future:futures) if(future.get(10,java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(3);
            assertThat(jdbc.sql("SELECT count(*) FROM schedule_history").query(Integer.class).single()).isEqualTo(3);
        }
    }
    @Test void independentRequestFiltersShareDatabaseQuota() throws Exception {
        var first=new com.greentech.scheduler_app.adapter.in.security.RequestLimitsFilter(jdbc,1);
        var second=new com.greentech.scheduler_app.adapter.in.security.RequestLimitsFilter(jdbc,1);
        String tenant="rate-"+UUID.randomUUID();
        int rejected=0;
        for(int i=0;i<20;i++) {
            var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/v1/tenants/"+tenant+"/schedules");
            var response=new org.springframework.mock.web.MockHttpServletResponse();
            (i%2==0?first:second).doFilter(request,response,(req,res)->{});
            if(response.getStatus()==429) { rejected++; assertThat(response.getHeader("Retry-After")).isEqualTo("1"); }
        }
        assertThat(rejected).isGreaterThan(0);
        assertThat(jdbc.sql("SELECT requests FROM tenant_request_window WHERE tenant_id=:tenant").param("tenant",tenant)
                .query(Integer.class).single()).isEqualTo(1);
    }
    @Test void runtimeRoleCanTransitionStateButCannotRewriteHistory() throws Exception {
        jdbc.sql("CREATE ROLE restricted_scheduler NOLOGIN").update();
        jdbc.sql("GRANT USAGE ON SCHEMA public TO restricted_scheduler").update();
        jdbc.sql("GRANT SELECT,INSERT,UPDATE ON schedule TO restricted_scheduler").update();
        jdbc.sql("GRANT SELECT ON schedule_history TO restricted_scheduler").update();
        try(var connection=java.sql.DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())) {
            connection.createStatement().execute("SET ROLE restricted_scheduler");
            var restricted=new JdbcScheduleRepository(JdbcClient.create(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true)));
            Instant now=Instant.now();
            Schedule schedule=Schedule.create("tenant-a","restricted",now.plusSeconds(60),"{}",now);
            restricted.insert(schedule);
            assertThat(restricted.update(schedule.cancel(now.plusSeconds(1)),1)).isTrue();
            assertThat(restricted.history("tenant-a","restricted",0,10)).hasSize(2);
            assertThatThrownBy(()->connection.createStatement().execute("DELETE FROM schedule_history WHERE false"))
                .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("permission denied");
        }
    }
}
