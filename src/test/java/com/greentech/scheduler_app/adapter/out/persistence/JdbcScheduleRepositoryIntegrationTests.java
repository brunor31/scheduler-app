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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
		Instant now = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
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
		UUID token = UUID.randomUUID();
		assertThat(repository.markDelivered("tenant-a", "schedule-1", 1, token, deliveredAt)).isFalse();
		assertThat(repository.tryClaim("tenant-a", "schedule-1", 1, token, Duration.ofSeconds(30))).isPresent();
		assertThat(repository.markDelivered("tenant-a", "schedule-1", 1, token, deliveredAt)).isTrue();
		assertThat(repository.markDelivered("tenant-a", "schedule-1", 1, token, deliveredAt)).isFalse();

		Schedule delivered = repository.findById("tenant-a", "schedule-1").orElseThrow();
		assertThat(delivered.state()).isEqualTo(ScheduleState.DELIVERED);
		assertThat(delivered.deliveredAt()).isEqualTo(deliveredAt);
	}

	@Test
	void reschedulesAndCancelsWithOptimisticVersionChecks() {
		Instant now = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		Schedule original = Schedule.create("tenant-a", "schedule-mutation", now.plusSeconds(30), "{}", now);
		repository.insert(original);

		Schedule rescheduled = original.reschedule(now.plusSeconds(60), now.plusSeconds(5));
		assertThat(repository.update(rescheduled, 1)).isTrue();
		assertThat(repository.update(rescheduled, 1)).isFalse();

		Schedule persisted = repository.findById("tenant-a", "schedule-mutation").orElseThrow();
		assertThat(persisted.version()).isEqualTo(2);
		assertThat(persisted.dueAt()).isEqualTo(now.plusSeconds(60));

		Schedule cancelled = persisted.cancel(now.plusSeconds(10));
		assertThat(repository.update(cancelled, 2)).isTrue();
		assertThat(repository.update(cancelled, 2)).isFalse();

		Schedule finalState = repository.findById("tenant-a", "schedule-mutation").orElseThrow();
		assertThat(finalState.version()).isEqualTo(3);
		assertThat(finalState.state()).isEqualTo(ScheduleState.CANCELLED);
		assertThat(finalState.cancelledAt()).isEqualTo(now.plusSeconds(10));
	}
}
