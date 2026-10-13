# ADR-0007 — Tenant authorization and registered destinations

## Status

Accepted; details ADR-0005 security boundaries already implemented.

## Context

The API can schedule future outbound calls. Cross-tenant access, arbitrary destinations, oversized input and leaked secrets would cross trust boundaries. Sources: SecurityConfiguration, RequestLimitsFilter, ConfiguredScheduleTarget, TargetProperties, application-prod.properties and deploy/runtime-role.sql.

## Decision

Use stateless OAuth2 resource-server JWT validation with signature, issuer, audience and expiry. Authorize tenant membership from tenant_id or tenants claims and require schedules:read, schedules:write or schedules:redrive. Metrics require scheduler:observe; only health endpoints are public in JWT mode. Disabled authentication requires local/test and is rejected when prod is active. JWT is the default; missing configuration fails closed. Production disables fake targets and Swagger by default.

Targets are operator-controlled IDs, not caller-supplied URLs. Each target has an explicit tenant allow-list, HTTPS URL, bounded timeout and per-process target/tenant semaphores. Private networking/internal HTTP requires explicit allow-private-network; link-local/unspecified/multicast destinations remain blocked. Reject URL credentials, query and fragment. Never follow redirects. Bearer credentials are looked up by SCHEDULER_TARGET_* environment reference and never persisted with schedules.

Application DNS validation is not a complete egress boundary: DNS re-resolution/rebinding and resolver latency remain concerns. TLS ingress/database configuration, certificate trust, approved network egress and real OIDC provisioning belong to deployment. Runtime DB identity differs from migrator/retention identities; no runtime DELETE of schedules or mutation of history.

## Alternatives and consequences

Unauthenticated tenant path parameters do not establish membership. Arbitrary URLs increase SSRF/secret-exfiltration risk. Embedding credentials in payloads persists sensitive material and complicates rotation. Operator-managed targets require configuration lifecycle/restart coordination. This release uses bearer references, not request signing or built-in secret-manager SDKs.

## Validation and limits

Signed RSA JWT tests cover issuer/audience/expiry/signature, scopes and cross-tenant rejection. HTTP tests cover status handling, redirect rejection, timeout and endpoint configuration. Runtime privilege tests check state transitions with append-only history. Real-provider rotation, TLS/egress enforcement and database isolation require staging checks. Authorization is application-enforced; PostgreSQL row-level security and human-actor audit are not implemented.
