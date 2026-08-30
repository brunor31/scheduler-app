package com.greentech.scheduler_app.adapter.out.persistence;

import com.greentech.scheduler_app.application.exception.ScheduleAlreadyExistsException;
import com.greentech.scheduler_app.application.port.out.ScheduleRepository;
import com.greentech.scheduler_app.domain.model.Schedule;
import com.greentech.scheduler_app.domain.model.ScheduleState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcScheduleRepository implements ScheduleRepository {

	private static final String SELECT_COLUMNS = """
			tenant_id, schedule_id, version, due_at, state, target_type, payload::text AS payload,
			created_at, updated_at, delivered_at
			""";

	private final JdbcClient jdbcClient;

	public JdbcScheduleRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public void insert(Schedule schedule) {
		try {
			jdbcClient.sql("""
					INSERT INTO schedule (
					    tenant_id, schedule_id, version, due_at, state, target_type, payload,
					    created_at, updated_at
					) VALUES (
					    :tenantId, :scheduleId, :version, :dueAt, :state, :targetType,
					    CAST(:payload AS jsonb), :createdAt, :updatedAt
					)
					""")
					.param("tenantId", schedule.tenantId())
					.param("scheduleId", schedule.scheduleId())
					.param("version", schedule.version())
					.param("dueAt", toDatabaseTimestamp(schedule.dueAt()))
					.param("state", schedule.state().name())
					.param("targetType", schedule.targetType())
					.param("payload", schedule.payload())
					.param("createdAt", toDatabaseTimestamp(schedule.createdAt()))
					.param("updatedAt", toDatabaseTimestamp(schedule.updatedAt()))
					.update();
		} catch (DuplicateKeyException exception) {
			throw new ScheduleAlreadyExistsException(schedule.tenantId(), schedule.scheduleId());
		}
	}

	@Override
	public Optional<Schedule> findById(String tenantId, String scheduleId) {
		return jdbcClient.sql("SELECT " + SELECT_COLUMNS + " FROM schedule WHERE tenant_id = :tenantId AND schedule_id = :scheduleId")
				.param("tenantId", tenantId)
				.param("scheduleId", scheduleId)
				.query(this::mapSchedule)
				.optional();
	}

	@Override
	public List<Schedule> findScheduledDueBefore(Instant horizon, int limit) {
		return jdbcClient.sql("""
				SELECT %s
				FROM schedule
				WHERE state = 'SCHEDULED' AND due_at <= :horizon
				ORDER BY due_at, tenant_id, schedule_id
				LIMIT :limit
				""".formatted(SELECT_COLUMNS))
				.param("horizon", toDatabaseTimestamp(horizon))
				.param("limit", limit)
				.query(this::mapSchedule)
				.list();
	}

	@Override
	public boolean markDelivered(String tenantId, String scheduleId, long expectedVersion, Instant deliveredAt) {
		return jdbcClient.sql("""
				UPDATE schedule
				SET state = 'DELIVERED', delivered_at = :deliveredAt, updated_at = :deliveredAt
				WHERE tenant_id = :tenantId
				  AND schedule_id = :scheduleId
				  AND version = :expectedVersion
				  AND state = 'SCHEDULED'
				""")
				.param("deliveredAt", toDatabaseTimestamp(deliveredAt))
				.param("tenantId", tenantId)
				.param("scheduleId", scheduleId)
				.param("expectedVersion", expectedVersion)
				.update() == 1;
	}

	private Schedule mapSchedule(ResultSet resultSet, int rowNumber) throws SQLException {
		return new Schedule(
				resultSet.getString("tenant_id"),
				resultSet.getString("schedule_id"),
				resultSet.getLong("version"),
				readInstant(resultSet, "due_at"),
				ScheduleState.valueOf(resultSet.getString("state")),
				resultSet.getString("target_type"),
				resultSet.getString("payload"),
				readInstant(resultSet, "created_at"),
				readInstant(resultSet, "updated_at"),
				readInstant(resultSet, "delivered_at")
		);
	}

	private static OffsetDateTime toDatabaseTimestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static Instant readInstant(ResultSet resultSet, String column) throws SQLException {
		OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
