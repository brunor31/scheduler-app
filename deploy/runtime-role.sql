-- Apply as schema owner AFTER Flyway. Provision LOGIN credentials externally and
-- GRANT scheduler_runtime to that login. Migrator and retention roles stay separate.
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='scheduler_runtime') THEN
    CREATE ROLE scheduler_runtime NOLOGIN;
  END IF;
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO scheduler_runtime;
GRANT SELECT, INSERT, UPDATE ON schedule TO scheduler_runtime;
GRANT SELECT ON schedule_history TO scheduler_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_request_window TO scheduler_runtime;
GRANT SELECT ON flyway_schema_history TO scheduler_runtime;
-- History writes execute through the schema-owner SECURITY DEFINER trigger.
-- Application has no DELETE on schedule and no mutation privileges on history.
