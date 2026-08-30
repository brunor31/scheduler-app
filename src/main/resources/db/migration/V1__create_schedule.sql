CREATE TABLE schedule (
    tenant_id      varchar(128) NOT NULL,
    schedule_id    varchar(128) NOT NULL,
    version        bigint NOT NULL,
    due_at         timestamptz NOT NULL,
    state          varchar(32) NOT NULL,
    target_type    varchar(32) NOT NULL,
    payload        jsonb NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    delivered_at   timestamptz,
    PRIMARY KEY (tenant_id, schedule_id),
    CONSTRAINT ck_schedule_version_positive CHECK (version > 0),
    CONSTRAINT ck_schedule_state CHECK (state IN ('SCHEDULED', 'DELIVERED')),
    CONSTRAINT ck_schedule_target_type CHECK (target_type = 'FAKE')
);

CREATE INDEX idx_schedule_due
    ON schedule (due_at, tenant_id, schedule_id)
    WHERE state = 'SCHEDULED';
