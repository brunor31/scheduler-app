package com.greentech.scheduler_app.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MigrationUpgradeIntegrationTests {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Test void upgradesExistingV3RowsWithoutChangingTheirIdentityStateOrPayload() {
        var source=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(source).target("3").load().migrate();
        var jdbc=JdbcClient.create(source);
        jdbc.sql("""
            INSERT INTO schedule(tenant_id,schedule_id,version,due_at,state,target_type,payload,created_at,updated_at)
            VALUES ('legacy','pending',7,clock_timestamp()+interval '1 hour','SCHEDULED','FAKE','{"keep":true}',clock_timestamp(),clock_timestamp())
            """).update();
        Flyway.configure().dataSource(source).load().migrate();
        var row=new JdbcScheduleRepository(jdbc).findById("legacy","pending").orElseThrow();
        assertThat(row.version()).isEqualTo(7);
        assertThat(row.targetId()).isEqualTo("fake");
        assertThat(row.state().name()).isEqualTo("SCHEDULED");
        assertThat(row.payload()).contains("true");
        assertThat(row.attempts()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM schedule_history").query(Integer.class).single()).isZero();
    }
}
