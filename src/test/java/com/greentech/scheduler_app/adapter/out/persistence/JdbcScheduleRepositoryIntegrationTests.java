package com.greentech.scheduler_app.adapter.out.persistence;

import com.greentech.scheduler_app.TestcontainersConfiguration;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.domain.model.Schedule;
import com.greentech.scheduler_app.domain.model.ScheduleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JdbcScheduleRepositoryIntegrationTests {

	@Autowired
	private ScheduleRepository repository;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clearDatabase() {
		jdbcClient.sql("TRUNCATE TABLE schedule").update();
	}

	@Test
	void persistsReadsAndConditionallyMarksDelivered() throws Exception {
		Instant now = Instant.parse("2026-08-29T18:00:00Z");
		Schedule schedule = Schedule.create("tenant-a", "schedule-1", now.plusSeconds(30), "{\"value\":1}", now);

		repository.insert(schedule);

		Schedule persisted = repository.findById("tenant-a", "schedule-1").orElseThrow();
		assertThat(persisted)
				.usingRecursiveComparison()
				.ignoringFields("payload")
				.isEqualTo(schedule);
		assertThat(objectMapper.readTree(persisted.payload()))
				.isEqualTo(objectMapper.readTree(schedule.payload()));

		Instant deliveredAt = now.plusSeconds(31);
		assertThat(repository.markDelivered("tenant-a", "schedule-1", 1, deliveredAt)).isTrue();
		assertThat(repository.markDelivered("tenant-a", "schedule-1", 1, deliveredAt)).isFalse();

		Schedule delivered = repository.findById("tenant-a", "schedule-1").orElseThrow();
		assertThat(delivered.state()).isEqualTo(ScheduleState.DELIVERED);
		assertThat(delivered.deliveredAt()).isEqualTo(deliveredAt);
	}
}
