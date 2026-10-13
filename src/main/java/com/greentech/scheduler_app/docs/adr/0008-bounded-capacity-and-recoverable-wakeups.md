# ADR-0008 — Bounded capacity and recoverable wake-ups

## Status

Accepted; updates historical defaults in ADR-0001/0002 and refines ADR-0005.

## Context

Unlimited timer loading, duplicated reconciliation and unbounded ready work would overload heap/database/destinations. Missing wake-ups must not lose durable work. Sources: LocalTimerScheduler, ScheduleRecoveryLoader, PostgresWakeupListener, RequestLimitsFilter, V4 admission trigger and application.properties.

## Decision

Keep one DelayQueue coordinator, a bounded ArrayBlockingQueue and four default workers per instance. Default ready queue is 1,000; active references are capped at 100,000 within a 15-minute look-ahead. Store IDs/version/deadline rather than payload in the timer. Deduplicate while queued/executing; replace superseded waiting entries opportunistically, while PostgreSQL predicates retain correctness.

Recovery uses keyset ordering by effective due time, tenant and ID: pages of 1,000, at most 100 pages per sweep, stopping when local capacity is exhausted. Recover SCHEDULED, due RETRY_WAIT and expired CLAIMED work; terminalize exhausted/expired candidates in bounded SKIP LOCKED batches. Five-second periodic reconciliation and startup loading repair missed publication. LISTEN/NOTIFY carries only a change hint; reconnect triggers reload. It is not a durable event bus or authorization to send.

Shared PostgreSQL request windows default to 100 requests/second/tenant. An advisory transaction lock serializes active admissions per tenant, default 100,000 active rows. JSON payloads are capped at 64 KiB, HTTP bodies at 80 KiB and headers at 16 KiB; new deadlines are future and at most 366 days ahead. Query pages are 1..200. HTTP semaphores default to eight per target and two per tenant/target per process. Deferral persists a later retry without spending an attempt. These are initial bounds, not measured production capacity guarantees.

Shutdown stops intake/coordinator and drains queued delivery work for at most 25 seconds; remaining claims rely on lease recovery. Lease defaults to 30 seconds, and configuration requires target timeout plus at least ten seconds. There is no lease renewal or ten-second failover guarantee.

## Alternatives and consequences

Unbounded queues hide overload in memory. Pure notification-driven recovery strands work after lost signals. Constant tight database polling adds idle pressure. Shards/fair queues may improve measured contention later but are not justified now. Quotas bound admission, not fair scheduling: early/backlogged tenants can dominate limited windows. Replica count multiplies target semaphore capacity and repeated database observations.

## Validation

Unit tests cover queue/backpressure/deduplication and memory bounds; PostgreSQL tests cover keyset deadlines and concurrent quotas. Two-process local acceptance covers process loss/recovery. Sustained load, noisy neighbors, missed notifications under outage and database failover remain staging workloads.
