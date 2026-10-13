# ADR-0009 — Transactional history and encrypted retention

## Status

Scope update (2026-10-13): the project owner requested removal of all scripts, including backup, restore and retention tools. Their descriptions and validation below remain historical evidence; the tools are no longer shipped. Replacement operational tooling must be provided and validated before production. See ADR-0011.

Accepted; elaborates ADR-0005 without enabling automatic deletion.

## Context

State changes need a consistent record while history/terminal schedules grow indefinitely. Retention must not race redrive or expose payloads. Sources: V4 migration, deploy/runtime-role.sql, scripts/ops/archive.py, backup.sh and restore.sh.

## Decision

A PostgreSQL AFTER INSERT/UPDATE trigger appends schedule_history atomically with the state write. Record tenant, schedule, version, states, attempt, sanitized reason and observed timestamp, without copying payload or secrets. The history trigger is SECURITY DEFINER with fixed public/pg_temp search_path. Separate schema-owner migration credentials, restricted runtime role and retention operator credentials. Runtime reads history but cannot rewrite it; this is append-only against that role, not tamper-proof against a database owner.

History/list endpoints use tenant-scoped cursor queries. History starts when V4 is installed; no retrospective events are invented. Human identity/justification is not captured by the trigger and must be handled in the incident process.

Archive bounded terminal batches before optional purge. Export schedule payload plus corresponding history from one SQL snapshot; encrypt through age without plaintext files, persist/fsync ciphertext and checksum, then optionally delete only rows still terminal with the exact exported identity/version/updated_at. Lock candidates before deleting history and schedules in one transaction. Concurrently modified/redriven rows remain.

No automatic retention interval is chosen. Policy must cover caller retry horizon, audit obligations and receiver deduplication. Deleting a row ends creation-idempotency memory for that tenant/id: an old POST may create new work. Redrive uses a new logical key even before retention.

Operational backup uses encrypted custom-format pg_dump. Restore verifies SHA-256, refuses a nonempty destination and uses one pg_restore transaction with ownership/ACL omitted. Reapply roles after restore. Retention archives have no automatic import; backup/PITR is the operational recovery mechanism. Keys must be held separately with tested recovery.

## Alternatives and consequences

Application-only audit insertion can diverge across write paths. Automatic TTL deletion could violate retention/idempotency contracts. Plain dumps expose payloads. Encryption/checksums do not replace access control, immutable storage or PITR. An interrupted export may leave a partial ciphertext file but must not authorize purge.

## Validation

Runtime-role tests prove automatic history without direct history mutation privileges. Local encrypted backup→empty restore→archive/decrypt→purge passed on an isolated database, including nonempty restore rejection. Real storage durability, key recovery, policy and RPO/RTO remain operational acceptance gates.
