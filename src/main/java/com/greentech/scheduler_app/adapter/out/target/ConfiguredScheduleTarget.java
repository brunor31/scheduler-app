package com.greentech.scheduler_app.adapter.out.target;

import com.greentech.scheduler_app.application.exception.DispatchFailureException;
import com.greentech.scheduler_app.application.port.out.ScheduleTarget;
import com.greentech.scheduler_app.application.port.out.TargetCatalog;
import com.greentech.scheduler_app.domain.exception.InvalidScheduleException;
import com.greentech.scheduler_app.domain.model.*;
import com.greentech.scheduler_app.infrastructure.config.TargetProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Component
public final class ConfiguredScheduleTarget implements ScheduleTarget, TargetCatalog, DisposableBean {
    private final TargetProperties properties;
    private final Map<String,Semaphore> targetSlots=new HashMap<>();
    private final Map<String,Map<String,Semaphore>> tenantSlots=new HashMap<>();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Clock clock;
    public ConfiguredScheduleTarget(TargetProperties properties,Clock clock) {
        this.properties=properties; this.clock=clock;
        properties.endpoints().forEach((id,endpoint)->{
            Schedule.requireId(id,"targetId");
            if(id.equals("fake")) throw new IllegalArgumentException("fake is a reserved target ID");
            validateEndpoint(endpoint);
            targetSlots.put(id,new Semaphore(endpoint.maxConcurrency()));
            Map<String,Semaphore> slots=new HashMap<>();
            for(String tenant:endpoint.tenants()) { Schedule.requireId(tenant,"tenant"); slots.put(tenant,new Semaphore(endpoint.tenantConcurrency())); }
            tenantSlots.put(id,Map.copyOf(slots));
        });
    }
    @Override public String typeFor(String tenant,String id) {
        if("fake".equals(id) && properties.fakeEnabled()) return "FAKE";
        var endpoint=properties.endpoints().get(id);
        if(endpoint==null || !endpoint.tenants().contains(tenant)) throw new InvalidScheduleException("target is unavailable for this tenant");
        return "HTTP";
    }
    @Override public void dispatch(Schedule schedule) {
        String type;
        try { type=typeFor(schedule.tenantId(),schedule.targetId()); }
        catch(InvalidScheduleException e) { throw failure(DispatchResult.Outcome.PERMANENT,"TARGET_UNAVAILABLE"); }
        if("FAKE".equals(type)) return;
        var endpoint=properties.endpoints().get(schedule.targetId());
        Semaphore target=targetSlots.get(schedule.targetId()), tenant=tenantSlots.get(schedule.targetId()).get(schedule.tenantId());
        if(!tenant.tryAcquire()) throw failure(DispatchResult.Outcome.DEFERRED,"TENANT_BUSY");
        boolean acquired=false;
        try {
            acquired=target.tryAcquire();
            if(!acquired) throw failure(DispatchResult.Outcome.DEFERRED,"TARGET_BUSY");
            checkAddress(endpoint);
            HttpRequest.Builder request=HttpRequest.newBuilder(endpoint.url()).timeout(endpoint.timeout())
                    .header("Content-Type","application/json")
                    .header("Idempotency-Key",schedule.idempotencyKey())
                    .header("X-Schedule-Id",schedule.scheduleId()).header("X-Schedule-Tenant",schedule.tenantId())
                    .header("X-Schedule-Version",Long.toString(schedule.version()))
                    .header("X-Delivery-Attempt",Integer.toString(schedule.attempts()))
                    .POST(HttpRequest.BodyPublishers.ofString(schedule.payload(),StandardCharsets.UTF_8));
            if(endpoint.bearerTokenEnv()!=null && !endpoint.bearerTokenEnv().isBlank()) {
                String secret=System.getenv(endpoint.bearerTokenEnv());
                if(secret==null || secret.isBlank()) throw failure(DispatchResult.Outcome.PERMANENT,"CREDENTIAL_UNAVAILABLE");
                request.header("Authorization","Bearer "+secret);
            }
            CompletableFuture<HttpResponse<Void>> future=client.sendAsync(request.build(),HttpResponse.BodyHandlers.discarding());
            HttpResponse<Void> response;
            try { response=future.get(endpoint.timeout().toMillis(),TimeUnit.MILLISECONDS); }
            catch(TimeoutException e) { future.cancel(true); throw failure(DispatchResult.Outcome.UNKNOWN,"HTTP_TIMEOUT"); }
            catch(InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw failure(DispatchResult.Outcome.UNKNOWN,"INTERRUPTED"); }
            catch(ExecutionException e) { throw failure(DispatchResult.Outcome.UNKNOWN,"HTTP_IO"); }
            int status=response.statusCode();
            if(status>=200 && status<300) return;
            DispatchResult.Outcome outcome=(status==408 || status==425 || status==429 || status>=500)
                    ?DispatchResult.Outcome.RETRYABLE:DispatchResult.Outcome.PERMANENT;
            throw new DispatchFailureException(new DispatchResult(outcome,"HTTP_"+status,
                    parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null),clock.instant())));
        } finally {
            if(acquired) target.release();
            tenant.release();
        }
    }
    private static void validateEndpoint(TargetProperties.Endpoint endpoint) {
        URI u=endpoint.url();
        if(u==null || u.getHost()==null || u.getUserInfo()!=null || u.getFragment()!=null || u.getRawQuery()!=null
                || (!"https".equals(u.getScheme()) && !(endpoint.allowPrivateNetwork() && "http".equals(u.getScheme())))) {
            throw new IllegalArgumentException("targets require HTTPS without credentials, query or fragment; local HTTP must explicitly allow private networking");
        }
        if(endpoint.tenants().isEmpty() || endpoint.maxConcurrency()<1 || endpoint.tenantConcurrency()<1
                || endpoint.timeout().toMillis()<100 || endpoint.timeout().compareTo(Duration.ofSeconds(20))>0) {
            throw new IllegalArgumentException("target requires tenants, positive concurrency and timeout 100ms..20s");
        }
        if(endpoint.bearerTokenEnv()!=null && !endpoint.bearerTokenEnv().isBlank()
                && !endpoint.bearerTokenEnv().matches("SCHEDULER_TARGET_[A-Z0-9_]+")) {
            throw new IllegalArgumentException("credential references must name a SCHEDULER_TARGET_* environment variable");
        }
    }
    private static void checkAddress(TargetProperties.Endpoint endpoint) {
        try {
            for(InetAddress address:InetAddress.getAllByName(endpoint.url().getHost())) {
                byte[] bytes=address.getAddress();
                // Cloud metadata/link-local is always forbidden, even for internal registered targets.
                if(address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress())
                    throw failure(DispatchResult.Outcome.PERMANENT,"DESTINATION_BLOCKED");
                boolean privateAddress=address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || (bytes.length==16 && (bytes[0]&0xfe)==0xfc)
                        || (bytes.length==4 && (bytes[0]&255)==100 && ((bytes[1]&255)>=64 && (bytes[1]&255)<=127));
                if(privateAddress && !endpoint.allowPrivateNetwork()) throw failure(DispatchResult.Outcome.PERMANENT,"DESTINATION_BLOCKED");
            }
        } catch(UnknownHostException e) { throw failure(DispatchResult.Outcome.RETRYABLE,"DNS_FAILURE"); }
    }
    public static Duration parseRetryAfter(String value,Instant now) {
        if(value==null) return null;
        try {
            Duration delay=value.trim().matches("[0-9]+")?Duration.ofSeconds(Long.parseLong(value.trim()))
                    :Duration.between(now,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative()?Duration.ZERO:delay.compareTo(Duration.ofDays(1))>0?Duration.ofDays(1):delay;
        } catch(RuntimeException e) { return null; }
    }
    private static DispatchFailureException failure(DispatchResult.Outcome outcome,String code) {
        return new DispatchFailureException(DispatchResult.of(outcome,code));
    }
    @Override public void destroy() { client.shutdownNow(); }
}
