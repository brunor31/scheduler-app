# ADR-0006 — Clean Architecture and PostgreSQL authority

## Status

Accepted; records the implemented foundation as of 2026-10-13.

## Context and evidence

The scheduler must preserve intent independently of HTTP delivery and disposable timers. Correctness depends on atomic version/state predicates, lease expiry and recoverable commits. Source evidence: domain/model, application/port, application/service, JdbcScheduleRepository, SchedulerConfiguration and V1–V4 Flyway migrations.

## Decision

Use one Spring Boot deployable with inward dependencies: adapters/infrastructure depend on application ports and domain objects. Domain records contain invariants/state transitions without Spring/JDBC/HTTP imports; services coordinate ports. Spring wiring stays in infrastructure. The JPA dependency remains available, but authoritative schedule access uses explicit JdbcClient SQL; no Hibernate schema generation is allowed.

PostgreSQL is the single authority for schedule identity `(tenant_id,schedule_id)`, version, state, deadlines, claims and history. JSON payloads use JSONB; timestamps use timestamptz at persistence boundaries and Instant in domain/application code. JDBC converts through UTC OffsetDateTime. The application injects Clock for business deadlines; PostgreSQL clock_timestamp governs claim/lease eligibility; local waiting uses monotonic nanoTime. This separation limits early claims but does not eliminate clock-skew effects on retry deadlines/observed metrics.

Persist before publishing to local memory. If a process fails between commit and enqueue, startup/periodic recovery repairs the missing local reference. No transaction spans an external HTTP call. The claim commits before dispatch; acknowledgement is a separate conditional write. Local queues cannot authorize execution independently.

Use ordered Flyway migrations, preserve deployed migrations and keep compatibility explicit. V1 creates the baseline, V2 cancellation, V3 claims, V4 delivery policies/history/quotas. Keep one region and interchangeable replicas without broker, shard leader or ownership layer until requirements/measurements justify more infrastructure.

## Alternatives and consequences

ORM-only state transitions obscure critical predicates; explicit SQL exposes them but creates PostgreSQL coupling and requires real-database tests. Memory-only scheduling loses confirmed intent on restart. A broker or microservice split adds another durability/operation boundary without current evidence. PostgreSQL remains a shared capacity/availability dependency; HA/PITR is an environment obligation, not supplied by this code.

## Validation and limits

Testcontainers covers real PostgreSQL migrations, conditional mutation, claims and recovery, including V3→V4 preservation. Domain/application tests isolate business behavior. The schema is not evidence of end-to-end exactly-once effects, strict fairness, infinite scale or cross-region recovery.
