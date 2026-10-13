package com.greentech.scheduler_app.adapter.in.web;

import com.greentech.scheduler_app.application.port.in.*;
import com.greentech.scheduler_app.application.service.ScheduleQueryService;
import com.greentech.scheduler_app.domain.exception.InvalidScheduleException;
import com.greentech.scheduler_app.domain.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/v1/tenants/{tenantId}/schedules")
@Tag(name="Schedules",description="Tenant-scoped schedules; retries share one idempotency key per version")
public class ScheduleController {
    private final CreateScheduleUseCase create;
    private final CancelScheduleUseCase cancel;
    private final RescheduleScheduleUseCase reschedule;
    private final ScheduleQueryService queries;
    private final ObjectMapper mapper;
    public ScheduleController(CreateScheduleUseCase create,CancelScheduleUseCase cancel,RescheduleScheduleUseCase reschedule,
            ScheduleQueryService queries,ObjectMapper mapper) {
        this.create=create; this.cancel=cancel; this.reschedule=reschedule; this.queries=queries; this.mapper=mapper;
    }
    @PostMapping
    @Operation(summary="Create or replay an idempotent request for the same tenant/scheduleId",
        description="An identical request returns the current representation; a conflicting body returns 409. Use a registered targetId.")
    @ApiResponses({@ApiResponse(responseCode="201",description="Schedule persisted"),
        @ApiResponse(responseCode="400",description="Invalid request"),@ApiResponse(responseCode="409",description="Identifier already used by another request")})
    public ResponseEntity<ScheduleResponse> create(@PathVariable String tenantId,@Valid @RequestBody CreateScheduleRequest request) {
        String payload=mapper.writeValueAsString(canonical(request.payload()==null?Map.of():request.payload()));
        Schedule schedule=create.create(new CreateScheduleCommand(tenantId,request.scheduleId(),request.dueAt(),payload,
                request.targetId(),request.retryPolicy(),request.maxLateness()));
        var location=UriComponentsBuilder.fromPath("/v1/tenants/{tenantId}/schedules/{scheduleId}")
                .buildAndExpand(tenantId,schedule.scheduleId()).encode().toUri();
        return ResponseEntity.created(location).eTag(Long.toString(schedule.version())).body(ScheduleResponse.from(schedule));
    }
    @GetMapping("/{scheduleId}")
    @Operation(summary="Get state, version, attempts and the next retry deadline")
    public ResponseEntity<ScheduleResponse> get(@PathVariable String tenantId,@PathVariable String scheduleId) {
        return response(queries.get(tenantId,scheduleId));
    }
    @GetMapping
    @Operation(summary="List schedules for a tenant; use nextCursor to retrieve the next page")
    public ScheduleQueryService.Page<ScheduleResponse> list(@PathVariable String tenantId,
            @RequestParam(required=false) ScheduleState state,@RequestParam(required=false) Instant dueFrom,
            @RequestParam(required=false) Instant dueTo,@RequestParam(required=false) String targetId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        var page=queries.list(tenantId,state,dueFrom,dueTo,targetId,cursor,limit);
        return new ScheduleQueryService.Page<>(page.items().stream().map(ScheduleResponse::from).toList(),page.nextCursor());
    }
    @GetMapping("/{scheduleId}/history")
    public ScheduleQueryService.Page<ScheduleHistory> history(@PathVariable String tenantId,@PathVariable String scheduleId,
            @RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="50") int limit) {
        return queries.history(tenantId,scheduleId,cursor,limit);
    }
    @PutMapping("/{scheduleId}")
    @Operation(summary="Reschedule before a claim; start a new version and retry budget")
    public ResponseEntity<ScheduleResponse> reschedule(@PathVariable String tenantId,@PathVariable String scheduleId,
            @RequestHeader("If-Match") String version,@Valid @RequestBody RescheduleScheduleRequest request) {
        return response(reschedule.reschedule(new RescheduleScheduleCommand(tenantId,scheduleId,parseVersion(version),request.dueAt())));
    }
    @PostMapping("/{scheduleId}:cancel")
    @Operation(summary="Cancel SCHEDULED or RETRY_WAIT; CLAIMED returns 409")
    public ResponseEntity<ScheduleResponse> cancel(@PathVariable String tenantId,@PathVariable String scheduleId,@RequestHeader("If-Match") String version) {
        return response(cancel.cancel(new CancelScheduleCommand(tenantId,scheduleId,parseVersion(version))));
    }
    @PostMapping("/{scheduleId}:redrive")
    @Operation(summary="Redrive dead-lettered or expired schedules with a new intent and idempotency key",
        description="Requires the schedules:redrive scope. A previous effect may already have occurred: review the history before redriving.")
    public ResponseEntity<ScheduleResponse> redrive(@PathVariable String tenantId,@PathVariable String scheduleId,
            @RequestHeader("If-Match") String version,@Valid @RequestBody RescheduleScheduleRequest request) {
        return response(queries.redrive(tenantId,scheduleId,parseVersion(version),request.dueAt()));
    }
    private static ResponseEntity<ScheduleResponse> response(Schedule s) {
        return ResponseEntity.ok().eTag(Long.toString(s.version())).body(ScheduleResponse.from(s));
    }
    static long parseVersion(String value) {
        String v=value.trim();
        if(v.startsWith("\"") && v.endsWith("\"") && v.length()>1) v=v.substring(1,v.length()-1);
        try { long version=Long.parseLong(v); if(version<1) throw new NumberFormatException(); return version; }
        catch(NumberFormatException e) { throw new InvalidScheduleException("If-Match must contain a positive numeric ETag"); }
    }
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) {
            Map<String,Object> result=new TreeMap<>(); map.forEach((k,v)->result.put(k.toString(),canonical(v))); return result;
        }
        if(value instanceof List<?> list) return list.stream().map(ScheduleController::canonical).toList();
        return value;
    }
    public record CreateScheduleRequest(@NotBlank String scheduleId,@NotNull Instant dueAt,Object payload,
            String targetId,RetryPolicy retryPolicy,Duration maxLateness) {}
    public record RescheduleScheduleRequest(@NotNull Instant dueAt) {}
    public record ScheduleResponse(String tenantId,String scheduleId,long version,Instant dueAt,String state,
            String targetType,String targetId,int attempts,Instant nextAttemptAt,String lastError,Instant deliveredAt,
            Instant cancelledAt,RetryPolicy retryPolicy,Duration maxLateness) {
        static ScheduleResponse from(Schedule s) {
            return new ScheduleResponse(s.tenantId(),s.scheduleId(),s.version(),s.dueAt(),s.state().name(),s.targetType(),
                    s.targetId(),s.attempts(),s.nextAttemptAt(),s.lastError(),s.deliveredAt(),s.cancelledAt(),s.retryPolicy(),s.maxLateness());
        }
    }
}
