# ADR-0010 — Single-region deployment and operational validation

## Status

Scope update (2026-10-13): the project owner requested removal of all scripts, including backup, restore and retention tools. Their descriptions and validation below remain historical evidence; the tools are no longer shipped. Replacement operational tooling must be provided and validated before production. See ADR-0011.

Accepted as packaging/operational contract; cloud deployment and production acceptance remain pending.

## Context

Functional tests cannot establish production capacity, failover SLOs or safe schema rollback. The project needs repeatable packaging, deployment boundaries and honest evidence. Sources: Dockerfile, deploy/kubernetes, deploy/monitoring, application-prod.properties, .github/workflows/verify.yml and src/test.

## Decision

Build a Java 21 Spring Boot jar and run it as non-root in a JRE container. Kubernetes templates use two replicas, bounded resources, read-only filesystem, temporary storage, readiness/liveness/startup probes, disruption budget, host spreading and graceful termination. These templates need real image digests, Secrets, target configuration and network rules; they do not provision HA PostgreSQL, TLS ingress, OIDC or cloud resources.

Run Flyway in a migration-only job using schema-owner credentials before application rollout. That mode starts no API/timer dispatch and exits after startup/migration. Runtime deployments disable Flyway and use restricted credentials. Stop incompatible pre-claim/pre-retry workers before initial migration; roll back only to binaries compatible with the schema and persisted states. Prefer forward fixes over automatic destructive migration rollback.

Readiness includes database and timer; liveness deliberately does not depend on PostgreSQL. Export JWT-protected Prometheus and structured production logs. Backlog gauges describe the same database across replicas and must be aggregated with max rather than sum. Avoid tenant/schedule labels. Initial alerts cover availability, overdue work, DLQ, metric sampling and unknown outcomes; thresholds/routing require operational validation.

CI runs Maven verification against Testcontainers PostgreSQL, builds the production image, emits SBOM and fails image scanning at High/Critical. The remote workflow has not been run in this session. Local chaos/load helpers remain separate and ignored by Git; CI must not depend on them. Do not gate ordinary correctness tests on noisy performance percentiles.

## Evidence and limits

The recorded full suite passed 160 tests; a later OpenAPI-only verification passed eight targeted tests. Two-process local acceptance with forced process loss passed. One 50-item HTTP burst delivered all items with completion p99 726.968 ms, while unrelated verification shared the host. These facts establish scoped local behavior, not sustained capacity or production SLOs. The historical fake-target benchmark is not the HTTP envelope.

## Alternatives and acceptance gates

Per-replica production migrations blur privilege boundaries. Liveness tied to database failure creates restart storms. Summing shared backlog exaggerates pending work. A managed scheduler/workflow product remains an option if business precision/cost favors it; no current provider comparison is asserted here.

Before real traffic, approve real OIDC/targets and destination idempotency, HA/PITR/TLS/egress/secrets, retention/RPO/RTO/SLOs, remote scan results, load/noisy-tenant/clock/database failure exercises, alert routing, rollout/rollback and joint business acceptance. Multi-region remains excluded.
