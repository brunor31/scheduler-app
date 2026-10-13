ALTER TABLE schedule
    ADD COLUMN target_id varchar(128) NOT NULL DEFAULT 'fake',
    ADD COLUMN max_attempts integer NOT NULL DEFAULT 5 CHECK (max_attempts BETWEEN 1 AND 100),
    ADD COLUMN initial_delay_ms bigint NOT NULL DEFAULT 1000,
    ADD COLUMN max_delay_ms bigint NOT NULL DEFAULT 60000,
    ADD COLUMN max_age_ms bigint NOT NULL DEFAULT 86400000,
    ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN last_error varchar(80),
    ADD COLUMN max_lateness_ms bigint,
    ADD COLUMN request_fingerprint varchar(64);
ALTER TABLE schedule DROP CONSTRAINT ck_schedule_state;
ALTER TABLE schedule ADD CONSTRAINT ck_schedule_state CHECK (state IN
    ('SCHEDULED', 'CLAIMED', 'RETRY_WAIT', 'DELIVERED', 'CANCELLED', 'DEAD_LETTERED', 'EXPIRED'));
ALTER TABLE schedule DROP CONSTRAINT ck_schedule_target_type;
ALTER TABLE schedule ADD CONSTRAINT ck_schedule_target_type CHECK (target_type IN ('FAKE', 'HTTP'));
ALTER TABLE schedule ADD CONSTRAINT ck_schedule_retry_policy CHECK (
    initial_delay_ms >= 100 AND max_delay_ms >= initial_delay_ms AND max_delay_ms <= 86400000
    AND max_age_ms BETWEEN 1000 AND 2592000000
    AND (max_lateness_ms IS NULL OR max_lateness_ms BETWEEN 1 AND 2592000000));
ALTER TABLE schedule ADD CONSTRAINT ck_schedule_retry_time CHECK (
    (state = 'RETRY_WAIT' AND next_attempt_at IS NOT NULL) OR state <> 'RETRY_WAIT');

CREATE INDEX idx_schedule_retry_due ON schedule (next_attempt_at, tenant_id, schedule_id) WHERE state = 'RETRY_WAIT';
CREATE INDEX idx_schedule_tenant_active ON schedule (tenant_id) WHERE state IN ('SCHEDULED','RETRY_WAIT','CLAIMED');
CREATE INDEX idx_schedule_tenant_list ON schedule (tenant_id, schedule_id, state);
CREATE INDEX idx_schedule_terminal_retention ON schedule (updated_at) WHERE state IN ('DELIVERED','CANCELLED','DEAD_LETTERED','EXPIRED');

CREATE TABLE schedule_history (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    schedule_id varchar(128) NOT NULL,
    version bigint NOT NULL,
    from_state varchar(32),
    to_state varchar(32) NOT NULL,
    attempt integer NOT NULL,
    reason varchar(80) NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_history_schedule ON schedule_history (tenant_id, schedule_id, event_id);
CREATE INDEX idx_history_retention ON schedule_history (observed_at);

-- History is committed atomically with the authoritative state; no payload, token or secret is copied.
CREATE FUNCTION scheduler_record_history() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
    INSERT INTO schedule_history(tenant_id,schedule_id,version,from_state,to_state,attempt,reason)
    VALUES (NEW.tenant_id,NEW.schedule_id,NEW.version,
        CASE WHEN TG_OP='INSERT' THEN NULL ELSE OLD.state END,NEW.state,NEW.attempts,
        CASE WHEN TG_OP='INSERT' THEN 'CREATED'
             WHEN OLD.state IN ('DEAD_LETTERED','EXPIRED') AND NEW.state='SCHEDULED' THEN 'REDRIVEN'
             WHEN NEW.version>OLD.version AND NEW.state='SCHEDULED' THEN 'RESCHEDULED'
             ELSE COALESCE(NEW.last_error,NEW.state) END);
    IF NEW.state IN ('SCHEDULED','RETRY_WAIT') THEN
        PERFORM pg_notify('scheduler_wakeup', 'changed');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_schedule_history AFTER INSERT OR UPDATE ON schedule
FOR EACH ROW EXECUTE FUNCTION scheduler_record_history();

CREATE FUNCTION scheduler_enforce_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE active_limit bigint;
BEGIN
    IF TG_OP='UPDATE' AND (OLD.state IN ('SCHEDULED','RETRY_WAIT','CLAIMED') OR NEW.state NOT IN ('SCHEDULED','RETRY_WAIT','CLAIMED')) THEN RETURN NEW; END IF;
    -- Serialize admissions per tenant across all application instances.
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id, 72493));
    IF TG_OP='INSERT' AND EXISTS (SELECT 1 FROM schedule WHERE tenant_id=NEW.tenant_id AND schedule_id=NEW.schedule_id) THEN
        RETURN NEW;
    END IF;
    active_limit := COALESCE(NULLIF(current_setting('scheduler.max_active_per_tenant', true),''),'100000')::bigint;
    IF NEW.state IN ('SCHEDULED','RETRY_WAIT','CLAIMED')
       AND (TG_OP='INSERT' OR OLD.state NOT IN ('SCHEDULED','RETRY_WAIT','CLAIMED'))
       AND (SELECT count(*) FROM schedule WHERE tenant_id=NEW.tenant_id AND state IN ('SCHEDULED','RETRY_WAIT','CLAIMED')) >= active_limit THEN
        RAISE EXCEPTION 'tenant active schedule quota exceeded' USING ERRCODE='P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_schedule_admission BEFORE INSERT OR UPDATE ON schedule
FOR EACH ROW EXECUTE FUNCTION scheduler_enforce_admission();

CREATE TABLE tenant_request_window (
    tenant_id varchar(128) PRIMARY KEY,
    window_start timestamptz NOT NULL,
    requests integer NOT NULL
);
