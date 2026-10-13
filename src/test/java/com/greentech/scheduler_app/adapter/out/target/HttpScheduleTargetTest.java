package com.greentech.scheduler_app.adapter.out.target;
import com.greentech.scheduler_app.application.exception.DispatchFailureException;
import com.greentech.scheduler_app.domain.model.*;
import com.greentech.scheduler_app.infrastructure.config.TargetProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class HttpScheduleTargetTest {
    HttpServer server;
    ConfiguredScheduleTarget target;
    ExecutorService workers;
    final AtomicInteger status=new AtomicInteger(200);
    final AtomicReference<String> key=new AtomicReference<>();
    final AtomicInteger calls=new AtomicInteger();
    final AtomicLong delay=new AtomicLong();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        workers=Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        server.createContext("/deliver",exchange->{
            calls.incrementAndGet(); key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(delay.get()); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.getResponseHeaders().add("Retry-After","2");
            exchange.getResponseHeaders().add("Location","/unexpected");
            exchange.sendResponseHeaders(status.get(),-1); exchange.close();
        }); server.start(); target=target(true,Duration.ofSeconds(2));
    }
    ConfiguredScheduleTarget target(boolean allowPrivate,Duration timeout) {
        var endpoint=new TargetProperties.Endpoint(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/deliver"),
                Set.of("tenant-a"),null,timeout,1,1,allowPrivate);
        return new ConfiguredScheduleTarget(new TargetProperties(false,Map.of("webhook",endpoint)),Clock.systemUTC());
    }
    Schedule schedule() { Instant now=Instant.now(); return Schedule.create("tenant-a","id",now.plusSeconds(1),"{}",now,"webhook","HTTP",RetryPolicy.DEFAULT,null,null); }
    @AfterEach void close() { target.destroy(); server.stop(0); workers.shutdownNow(); }
    @Test void sendsStableKeyAndRejectsUnregisteredTenant() {
        Schedule s=schedule(); target.dispatch(s); target.dispatch(s);
        assertThat(key.get()).isEqualTo(s.idempotencyKey()); assertThat(calls).hasValue(2);
        assertThatThrownBy(()->target.typeFor("other-tenant","webhook")).isInstanceOf(com.greentech.scheduler_app.domain.exception.InvalidScheduleException.class);
    }
    @Test void classifies429AndHonorsRetryAfter() {
        status.set(429);
        assertThatThrownBy(()->target.dispatch(schedule())).isInstanceOfSatisfying(DispatchFailureException.class,e->{
            assertThat(e.result().outcome()).isEqualTo(DispatchResult.Outcome.RETRYABLE);
            assertThat(e.result().retryAfter()).isEqualTo(Duration.ofSeconds(2));
        });
    }
    @Test void doesNotFollowRedirectAndDoesNotRetryPermanent400() {
        for(int code:List.of(302,400)) {
            status.set(code);
            assertThatThrownBy(()->target.dispatch(schedule())).isInstanceOfSatisfying(DispatchFailureException.class,
                e->assertThat(e.result().outcome()).isEqualTo(DispatchResult.Outcome.PERMANENT));
        }
        assertThat(calls).hasValue(2);
    }
    @Test void boundsRequestTimeAndTreatsTimeoutAsUnknown() {
        target.destroy(); target=target(true,Duration.ofMillis(200));
        delay.set(1000);
        assertThatThrownBy(()->target.dispatch(schedule())).isInstanceOfSatisfying(DispatchFailureException.class,
            e->assertThat(e.result().outcome()).isEqualTo(DispatchResult.Outcome.UNKNOWN));
    }
    @Test void refusesInsecureTargetUnlessExplicitlyInternal() {
        assertThatThrownBy(()->target(false,Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void parsesDateRetryAfterAndCapsDelay() {
        Instant now=Instant.parse("2026-10-13T12:00:00Z");
        assertThat(ConfiguredScheduleTarget.parseRetryAfter("Tue, 13 Oct 2026 12:00:05 GMT",now)).isEqualTo(Duration.ofSeconds(5));
        assertThat(ConfiguredScheduleTarget.parseRetryAfter("9999999",now)).isEqualTo(Duration.ofDays(1));
        assertThat(ConfiguredScheduleTarget.parseRetryAfter("invalid",now)).isNull();
    }
}
