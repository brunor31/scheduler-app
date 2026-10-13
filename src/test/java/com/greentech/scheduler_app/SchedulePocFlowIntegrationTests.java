package com.greentech.scheduler_app;

import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.domain.model.Schedule;
import com.greentech.scheduler_app.domain.model.ScheduleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
class SchedulePocFlowIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ScheduleRepository repository;

	@Autowired
	private JdbcClient jdbcClient;

	@BeforeEach
	void clearDatabase() {
		jdbcClient.sql("TRUNCATE TABLE schedule").update();
	}

	@Test
	void exposesOpenApiContractForScheduleCreation() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("Durable Scheduling Platform API"))
				.andExpect(jsonPath("$['paths']['/v1/tenants/{tenantId}/schedules']['post']").exists())
				.andExpect(jsonPath("$['paths']['/v1/tenants/{tenantId}/schedules']['post']['responses']['201']").exists())
				.andExpect(jsonPath("$['paths']['/v1/tenants/{tenantId}/schedules']['post']['responses']['400']").exists())
				.andExpect(jsonPath("$['paths']['/v1/tenants/{tenantId}/schedules']['post']['responses']['409']").exists());
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$['paths']['/v1/tenants/{tenantId}/schedules/{scheduleId}']['put']").exists())
				.andExpect(jsonPath("$['paths']['/v1/tenants/{tenantId}/schedules/{scheduleId}:cancel']['post']").exists());

		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());
	}

	@Test
	void createsPersistsAndExecutesSchedule() throws Exception {
		Instant dueAt = Instant.now().plusMillis(750);
		mockMvc.perform(post("/v1/tenants/tenant-a/schedules")
					.contentType("application/json")
					.content("""
							{
							  "scheduleId": "schedule-flow-1",
							  "dueAt": "%s",
							  "payload": {"kind": "test"}
							}
							""".formatted(dueAt)))
				.andExpect(status().isCreated())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.state").value("SCHEDULED"));

		Schedule delivered = waitForDelivered("tenant-a", "schedule-flow-1", Duration.ofSeconds(5));
		assertThat(delivered.state()).isEqualTo(ScheduleState.DELIVERED);
		assertThat(delivered.deliveredAt()).isAfterOrEqualTo(dueAt);
	}

	@Test
	void reschedulesAndLazilyIgnoresOriginalTimerVersion() throws Exception {
		Instant originalDueAt = Instant.now().plusMillis(800);
		Instant rescheduledDueAt = Instant.now().plusMillis(1_800);
		createSchedule("schedule-reschedule", originalDueAt);

		mockMvc.perform(put("/v1/tenants/tenant-a/schedules/schedule-reschedule")
					.header("If-Match", "\"1\"")
					.contentType("application/json")
					.content("""
							{"dueAt":"%s"}
							""".formatted(rescheduledDueAt)))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.state").value("SCHEDULED"));

		Thread.sleep(1_000);
		Schedule afterOriginalTimer = repository.findById("tenant-a", "schedule-reschedule").orElseThrow();
		assertThat(afterOriginalTimer.state()).isEqualTo(ScheduleState.SCHEDULED);
		assertThat(afterOriginalTimer.version()).isEqualTo(2);

		Schedule delivered = waitForDelivered("tenant-a", "schedule-reschedule", Duration.ofSeconds(5));
		assertThat(delivered.version()).isEqualTo(2);
		assertThat(delivered.deliveredAt()).isAfterOrEqualTo(rescheduledDueAt);
	}

	@Test
	void cancelsAndLazilyPreventsTimerDelivery() throws Exception {
		createSchedule("schedule-cancel", Instant.now().plusMillis(700));

		mockMvc.perform(post("/v1/tenants/tenant-a/schedules/schedule-cancel:cancel")
					.header("If-Match", "\"1\""))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.state").value("CANCELLED"));

		Thread.sleep(900);
		Schedule cancelled = repository.findById("tenant-a", "schedule-cancel").orElseThrow();
		assertThat(cancelled.state()).isEqualTo(ScheduleState.CANCELLED);
		assertThat(cancelled.cancelledAt()).isNotNull();
		assertThat(cancelled.deliveredAt()).isNull();
	}

	@Test
	void rejectsMutationWithStaleVersion() throws Exception {
		createSchedule("schedule-stale", Instant.now().plusSeconds(5));

		mockMvc.perform(put("/v1/tenants/tenant-a/schedules/schedule-stale")
					.header("If-Match", "\"9\"")
					.contentType("application/json")
					.content("""
							{"dueAt":"%s"}
							""".formatted(Instant.now().plusSeconds(10))))
				.andExpect(status().isPreconditionFailed());
	}

	@Test
	void reschedulesTwiceAndOnlyDeliversLatestVersion() throws Exception {
		Instant originalDueAt = Instant.now().plusMillis(700);
		Instant secondDueAt = Instant.now().plusMillis(1_200);
		Instant thirdDueAt = Instant.now().plusMillis(1_800);
		createSchedule("schedule-reschedule-twice", originalDueAt);

		reschedule("schedule-reschedule-twice", 1, secondDueAt, 2);
		reschedule("schedule-reschedule-twice", 2, thirdDueAt, 3);

		Thread.sleep(1_400);
		Schedule afterStaleTimers = repository.findById("tenant-a", "schedule-reschedule-twice").orElseThrow();
		assertThat(afterStaleTimers.state()).isEqualTo(ScheduleState.SCHEDULED);
		assertThat(afterStaleTimers.version()).isEqualTo(3);

		Schedule delivered = waitForDelivered("tenant-a", "schedule-reschedule-twice", Duration.ofSeconds(5));
		assertThat(delivered.version()).isEqualTo(3);
		assertThat(delivered.deliveredAt()).isAfterOrEqualTo(thirdDueAt);
	}

	@Test
	void reportsNotFoundInvalidEtagAndStateConflict() throws Exception {
		mockMvc.perform(post("/v1/tenants/tenant-a/schedules/missing:cancel")
					.header("If-Match", "\"1\""))
				.andExpect(status().isNotFound());

		createSchedule("schedule-errors", Instant.now().plusSeconds(5));
		mockMvc.perform(post("/v1/tenants/tenant-a/schedules/schedule-errors:cancel")
					.header("If-Match", "invalid"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(post("/v1/tenants/tenant-a/schedules/schedule-errors:cancel")
					.header("If-Match", "\"1\""))
				.andExpect(status().isOk());
		mockMvc.perform(post("/v1/tenants/tenant-a/schedules/schedule-errors:cancel")
					.header("If-Match", "\"2\""))
				.andExpect(status().isConflict());
	}

	@Test
	void rejectsCancelAndRescheduleAfterClaimWithHttp409() throws Exception {
		Instant now = Instant.now();
		repository.insert(Schedule.create("tenant-a", "claimed", now.minusSeconds(1), "{}", now.minusSeconds(2)));
		assertThat(repository.tryClaim("tenant-a", "claimed", 1, java.util.UUID.randomUUID(), Duration.ofMinutes(1)))
				.isPresent();

		mockMvc.perform(post("/v1/tenants/tenant-a/schedules/claimed:cancel").header("If-Match", "\"1\""))
				.andExpect(status().isConflict());
		mockMvc.perform(put("/v1/tenants/tenant-a/schedules/claimed")
				.header("If-Match", "\"1\"")
				.contentType("application/json")
				.content("{\"dueAt\":\"" + now.plusSeconds(60) + "\"}"))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/v1/tenants/tenant-a/schedules/claimed:cancel").header("If-Match", "\"2\""))
				.andExpect(status().isPreconditionFailed());
	}

	private void createSchedule(String scheduleId, Instant dueAt) throws Exception {
		mockMvc.perform(post("/v1/tenants/tenant-a/schedules")
					.contentType("application/json")
					.content("""
							{
							  "scheduleId": "%s",
							  "dueAt": "%s",
							  "payload": {"kind": "test"}
							}
							""".formatted(scheduleId, dueAt)))
				.andExpect(status().isCreated());
	}

	private void reschedule(String scheduleId, long expectedVersion, Instant dueAt, long resultingVersion)
			throws Exception {
		mockMvc.perform(put("/v1/tenants/tenant-a/schedules/{scheduleId}", scheduleId)
					.header("If-Match", "\"" + expectedVersion + "\"")
					.contentType("application/json")
					.content("""
							{"dueAt":"%s"}
							""".formatted(dueAt)))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"" + resultingVersion + "\""))
				.andExpect(jsonPath("$.version").value(resultingVersion));
	}

	private Schedule waitForDelivered(String tenantId, String scheduleId, Duration timeout) throws InterruptedException {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			Schedule schedule = repository.findById(tenantId, scheduleId).orElse(null);
			if (schedule != null && schedule.state() == ScheduleState.DELIVERED) {
				return schedule;
			}
			Thread.sleep(25);
		}
		return repository.findById(tenantId, scheduleId).orElseThrow();
	}
}
