# ADR-0005 — Durable HTTP delivery for the first single-region release

Status: implemented; production environment acceptance pending. Date: 2026-10-13.

## Decision relationships

This decision updates ADR-0002's overdue policy, ADR-0003's mutable states and ADR-0004's failure handling while preserving their original evidence. ADR-0006 records foundational boundaries/storage, ADR-0007 security, ADR-0008 capacity/recovery, ADR-0009 audit/retention and ADR-0010 operations/validation. ADR-0011 governs documentation ownership. These focused records elaborate this release decision; they do not introduce unimplemented infrastructure.

## Problem and evidence

ADR-0004 established claims and attempt-token fencing but left delivery fake, outcomes transient, recovery bounded to an incomplete loading strategy and endpoints unauthenticated. The product discovery calls for durable scheduling with recovery and operational visibility. The user explicitly excluded multi-region and authorized implementation of the remaining first-release capabilities. Existing PostgreSQL and bounded workers can support that scope without another broker or coordination service.

## Decision

Keep PostgreSQL authoritative and multiple interchangeable application replicas in one region. Use registered HTTP/HTTPS destinations as the first real transport. No Kafka, SQS transport, sharding, lease renewal or regional replication is introduced. Future transports need their own requirements and evidence.

Persist attempt count, policy, effective retry deadline, target ID, sanitized error, optional first-attempt lateness bound and creation fingerprint. States are SCHEDULED, CLAIMED, RETRY_WAIT, DELIVERED, CANCELLED, DEAD_LETTERED and EXPIRED. Claim atomically checks due time, retry/age budget and lease; completion/failure requires the current unexpired token. Recovery terminalizes exhausted/expired work in bounded batches. Defaults: five claims, equal-jitter exponential backoff from one second capped at one minute, maximum age one day from dueAt. HTTP Retry-After is capped at one day and can lengthen the backoff; the age budget still applies.

2xx is accepted delivery acknowledgement. 408/425/429/5xx are retryable; other non-2xx statuses, redirects and unavailable/forbidden destination configuration are permanent failures. Redirects are never followed. Timeouts/connection loss/interruption are UNKNOWN, with the same durable retry path: the effect may already have happened. Local concurrency deferral does not consume an attempt, but still obeys age limits. A crashed claim consumes an attempt even if sending never began. DEAD_LETTERED means operator attention, not proof that the destination had no effect.

The destination receives a SHA-256 key derived from length-delimited tenant ID, schedule ID and version. Every attempt of one version has the same key. This implements at-least-once delivery within configured budgets; destination-side durable deduplication is required for a single business effect. Database fencing cannot revoke a request already sent or fence a remote destination.

Cancel/reschedule is allowed in SCHEDULED or RETRY_WAIT and guarded by expected version. CLAIMED and terminal states reject those mutations. Retry cancellation prevents future retries, but cannot undo an earlier ambiguous external effect. Reschedule and explicit redrive increment version/reset attempts and create a new logical intent/key. Redrive accepts only DEAD_LETTERED/EXPIRED, requires its own scope and a new future deadline. An operator must reconcile unknown effects before redrive. Cancel preserves the previous attempt count.

Creation fingerprint covers canonical JSON payload, original dueAt, target identity/type and policies. Replaying the same creation returns the current resource, even after rescheduling/cancellation and after its original deadline; conflicting creation returns 409. Both initial creation and replay return 201 for backward API compatibility. Rows migrated from the POC have no reconstructed fingerprint. Deleting a terminal row after archival ends API creation deduplication for that tenant/id; retention policy must account for caller retry horizons and destination key retention.

## Recovery, capacity and security

Memory stores bounded timer references within a 15-minute look-ahead. Keyset paging traverses equal deadlines without offset loss; duplicate references do not spend capacity twice. New versions replace older waiting entries. Deferred/not-loaded work remains durable for later sweeps. PostgreSQL LISTEN/NOTIFY is a latency hint with no schedule contents; reconnect and five-second periodic reconciliation recover missed signals. Local successful failure handling promptly reloads the retry deadline. Drain is bounded; abandoned claims recover after lease expiry. A 30-second lease does not meet a ten-second failover promise.

JWT validates signature, issuer, audience and expiration; tenant claims and scopes govern requests. Disabled authentication is restricted to explicit local/test profiles and prohibited when prod is also active. Destinations are configuration-owned, tenant allow-listed, HTTPS by default, without URL credentials/query/fragment; bearer secrets are environment references. Destination and per-tenant concurrency are bounded per process. DNS checks reject prohibited addresses, but egress restrictions remain required to address rebinding and connection resolution. Payload/body/header size and shared database request/active quotas bound admission. These controls do not establish strict tenant scheduling fairness.

A transactional trigger appends history without payload/secrets and emits wake-up hints. History trigger runs as schema owner with fixed search_path so the runtime DB role can read history without rewriting it. Runtime credentials, migrator credentials and retention operator credentials are separate. This history records transitions and reasons; human actor/justification belongs to the incident process for this release.

## Operations and consequences

Add container packaging, two-process local acceptance, immutable-image deployment templates, migration-only mode, JWT-protected Prometheus, structured prod logs and initial alerts. Backup/retention streams are encrypted with age; purge locks and compares exported version/update timestamp before deleting, preserving concurrent redrives. No automatic purge is enabled without the retention policy. HA/PITR, ingress TLS, egress rules, OIDC and secret provisioning are environment tasks; Kubernetes templates do not provision them.

The added writes/trigger/quota checks change the measured envelope. Earlier fake-target benchmark results are historical and cannot be advertised for the HTTP path. There is no claim of exactly-once execution or production SLO compliance. Registry/secret rotation, realistic workload, DB outage/failover, rollout and restore must be exercised in staging before real traffic.

## Alternatives

A broker plus separate dispatcher adds another durability boundary before measured need. A distributed timer/leader/shard layer adds coordination without current evidence. Retrying only in memory loses policy/deadline on crashes. Arbitrary caller-supplied target URLs weaken destination control. None is selected for this release.

## Validation

160 automated tests pass, including PostgreSQL claim races and stale completions, concurrent tenant quota, shared request throttling, runtime audit privileges, V3→V4 data preservation, memory bounds, retry/age/misfire/DLQ/redrive, signed JWT isolation and HTTP outcomes. Real two-process Compose acceptance passed with both processes killed during a claim and recovered, while the sample receiver recorded one effect per key. Encrypted backup→isolated restore→archive→purge was exercised; nonempty restore and runtime history deletion were rejected. Migration-only mode exited successfully without workers/API. A 50-item HTTP smoke burst delivered all records; results are documented in the operating envelope. CI configuration is present but its remote scan has not run in this session.
