ALTER TABLE schedule
    ADD COLUMN attempt_token uuid,
    ADD COLUMN lease_expires_at timestamptz;

ALTER TABLE schedule DROP CONSTRAINT ck_schedule_state;

ALTER TABLE schedule
    ADD CONSTRAINT ck_schedule_state
        CHECK (state IN ('SCHEDULED', 'CLAIMED', 'DELIVERED', 'CANCELLED')),
    ADD CONSTRAINT ck_schedule_claim
        CHECK (
            (state = 'CLAIMED' AND attempt_token IS NOT NULL AND lease_expires_at IS NOT NULL)
            OR (state <> 'CLAIMED' AND attempt_token IS NULL AND lease_expires_at IS NULL)
        );

CREATE INDEX idx_schedule_expired_claim
    ON schedule (lease_expires_at)
    WHERE state = 'CLAIMED';
