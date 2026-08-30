package com.greentech.scheduler_app.adapter.in.web;

import com.greentech.scheduler_app.application.port.in.CreateScheduleCommand;
import com.greentech.scheduler_app.application.port.in.CreateScheduleUseCase;
import com.greentech.scheduler_app.domain.model.Schedule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/v1/tenants/{tenantId}/schedules")
@Tag(name = "Schedules", description = "Criação e gerenciamento de schedules por tenant")
public class ScheduleController {

	private final CreateScheduleUseCase createSchedule;
	private final ObjectMapper objectMapper;

	public ScheduleController(CreateScheduleUseCase createSchedule, ObjectMapper objectMapper) {
		this.createSchedule = createSchedule;
		this.objectMapper = objectMapper;
	}

	@PostMapping
	@Operation(
			summary = "Cria um schedule",
			description = "Persiste um schedule futuro e o registra no timer local. "
					+ "O PostgreSQL permanece como fonte de verdade."
	)
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Schedule criado",
					headers = {
							@Header(name = "Location", description = "URI do schedule criado", schema = @Schema(type = "string")),
							@Header(name = "ETag", description = "Versão atual do schedule", schema = @Schema(type = "string", example = "\"1\""))
					},
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ScheduleResponse.class),
							examples = @ExampleObject(value = """
									{
									  "tenantId": "tenant-a",
									  "scheduleId": "schedule-001",
									  "version": 1,
									  "dueAt": "2030-01-01T12:00:00Z",
									  "state": "SCHEDULED",
									  "targetType": "FAKE"
									}
									""")
				)
			),
			@ApiResponse(
					responseCode = "400",
					description = "Requisição inválida ou dueAt não está no futuro",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))
			),
			@ApiResponse(
					responseCode = "409",
					description = "Já existe um schedule com o mesmo tenantId e scheduleId",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))
			)
	})
	public ResponseEntity<ScheduleResponse> create(
			@Parameter(description = "Identificador do tenant", example = "tenant-a", required = true)
			@PathVariable @NotBlank String tenantId,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					required = true,
					description = "Schedule futuro e payload que será entregue ao destino",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CreateScheduleRequest.class),
							examples = @ExampleObject(value = """
									{
									  "scheduleId": "schedule-001",
									  "dueAt": "2030-01-01T12:00:00Z",
									  "payload": {"kind": "manual-test"}
									}
									""")
					)
			)
			@Valid @RequestBody CreateScheduleRequest request
	) throws JacksonException {
		String payload = objectMapper.writeValueAsString(request.payload() == null ? new Object() : request.payload());
		Schedule schedule = createSchedule.create(new CreateScheduleCommand(
				tenantId,
				request.scheduleId(),
				request.dueAt(),
				payload
		));

		URI location = UriComponentsBuilder
				.fromPath("/v1/tenants/{tenantId}/schedules/{scheduleId}")
				.buildAndExpand(schedule.tenantId(), schedule.scheduleId())
				.encode()
				.toUri();
		return ResponseEntity.created(location)
				.eTag(Long.toString(schedule.version()))
				.body(ScheduleResponse.from(schedule));
	}

	public record CreateScheduleRequest(
			@Schema(description = "Identificador único do schedule dentro do tenant", example = "schedule-001")
			@NotBlank String scheduleId,
			@Schema(description = "Instante futuro em UTC no formato ISO-8601", example = "2030-01-01T12:00:00Z")
			@NotNull Instant dueAt,
			@Schema(description = "Objeto JSON opaco preservado até a entrega", example = "{\"kind\":\"manual-test\"}")
			Object payload
	) {
	}

	@Schema(description = "Representação do schedule recém-criado")
	public record ScheduleResponse(
			@Schema(example = "tenant-a") String tenantId,
			@Schema(example = "schedule-001") String scheduleId,
			@Schema(example = "1") long version,
			@Schema(example = "2030-01-01T12:00:00Z") Instant dueAt,
			@Schema(example = "SCHEDULED", allowableValues = {"SCHEDULED", "DELIVERED"}) String state,
			@Schema(example = "FAKE", allowableValues = "FAKE") String targetType
	) {
		static ScheduleResponse from(Schedule schedule) {
			return new ScheduleResponse(
					schedule.tenantId(),
					schedule.scheduleId(),
					schedule.version(),
					schedule.dueAt(),
					schedule.state().name(),
					schedule.targetType()
			);
		}
	}
}
