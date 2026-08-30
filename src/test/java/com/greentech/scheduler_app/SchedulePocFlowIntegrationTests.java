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
