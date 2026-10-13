ALTER TABLE schedule
    ADD COLUMN cancelled_at timestamptz;

ALTER TABLE schedule
    DROP CONSTRAINT ck_schedule_state;

ALTER TABLE schedule
    ADD CONSTRAINT ck_schedule_state
        CHECK (state IN ('SCHEDULED', 'DELIVERED', 'CANCELLED')),
    ADD CONSTRAINT ck_schedule_cancelled_at
        CHECK (
            (state = 'CANCELLED' AND cancelled_at IS NOT NULL)
            OR (state <> 'CANCELLED' AND cancelled_at IS NULL)
        );
