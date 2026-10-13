# ADR-0004 — Atomic delivery claim and cancellation boundary

## Status

Accepted for POC-3. Supersedes ADR-0003's eligibility-read boundary and ADR-0002's SCHEDULED-only recovery. Its claim/token/lease and no-open-transaction-during-send decisions remain active. ADR-0005 supersedes the failure handling and missing-HTTP/retry limitations: handled failures persist RETRY_WAIT or DEAD_LETTERED instead of necessarily waiting for lease expiry. Unacknowledged/crashed claims still recover after expiry. ADR-0008 replaces the single-batch/60-second recovery settings. ADR-0010 records production migration and rollout requirements.

## Problem and evidence

The previous handler read SCHEDULED/version, invoked the target, then conditionally marked delivery. A cancellation or reschedule could commit between the read and the call; two workers could also both read the same eligible version and dispatch. A conditional completion does not undo an external side effect. POC-2 explicitly left this race unresolved.

## Decision

Use a single PostgreSQL `UPDATE ... RETURNING` as the claim boundary, matching tenant, ID, expected version, due time and eligible state. Only its winner may dispatch. The transition to CLAIMED commits before the target call; no database transaction remains open across the external call.

Cancellation and rescheduling still require SCHEDULED and the expected version. The first successful conditional database update wins:

- mutation wins: the old timer cannot claim;
- claim wins: mutation returns HTTP 409 for the same version, even before the target call starts;
- stale expected version: HTTP 412;
- missing schedule: HTTP 404.

Claim and completion do not increment the logical schedule version. This version describes client scheduling intent. A failed mutation CAS reloads the row to distinguish a state conflict from a version conflict.

Each claim carries a fresh UUID attempt token and an expiry. The default `scheduler.delivery.claim-lease` is 30 seconds. Due-time eligibility, lease acquisition and expiry checks use the PostgreSQL clock, so application clock differences do not grant early claims or extend ownership. Durations must be at least one millisecond.

Completion requires CLAIMED, the same tenant/ID/version/token and an unexpired lease. It clears the token and expiry atomically. The attempt token protects database confirmation against an older attempt; it is not a global fencing token and is not the target's logical idempotency key.

## Recovery

The existing startup/periodic loader includes expired CLAIMED rows alongside SCHEDULED rows. A later claim atomically replaces the expired attempt token. Unexpired claims are excluded. Failure or unknown target outcome leaves the claim persisted until expiry; it is not immediately released, because an external side effect may already have happened.

The current single bounded batch and reconciliation interval remain unchanged. With the defaults, takeover may wait for the remaining 30-second lease plus a 60-second reconciliation interval, with additional delay from backlog/startup. This is not the provisional 10-second failover SLO. The historical POC-1 measurements only cover unclaimed schedules.

## Invariants and limits

- Concurrent claims for the same version have at most one winner while that claim remains valid.
- No target call occurs when the handler fails to claim.
- A successful cancellation prevents a subsequent claim for that version.
- An old or expired attempt cannot confirm a newer attempt's delivery.
- The claim boundary deliberately closes cancellation earlier than the first external side effect.
- Delivery remains at-least-once. A target success followed by loss of confirmation can be sent again after expiry.
- There is no lease renewal, target timeout/cancellation, logical delivery idempotency, retry classification/backoff/budget, or DLQ in this POC.
- A paused or slow worker can resume or remain inside the target after lease expiry. Another worker may then reclaim and send. The token cannot prevent or undo those external effects; a real target needs a stable idempotency contract and a measured execution/lease policy.
- Local active-key deduplication remains. Recovery in another process after loss of the owner is possible through PostgreSQL; an indefinitely blocked local worker is not repaired by this mechanism alone.
- PostgreSQL is the lease time authority; database clock jumps/failover behavior still need validation.
- Single-region is the first-production scope. No regional replication, cross-region failover or global coordination is introduced.

## Added complexity and alternatives

One lifecycle state, two nullable columns, one partial index and token-aware claim/completion queries are added. A finite per-attempt lease avoids permanently stranding work when a process dies after claiming.

A claim without expiry would strand schedules after crashes. Keeping a transaction open during target delivery would hold row locks and still would not make the external effect atomic. Shard leases, SKIP LOCKED batch claims and a coordinator are deferred until multi-process/load experiments justify them. No broker or new dependency is added.

## Deployment compatibility

V3 preserves existing SCHEDULED, CANCELLED and DELIVERED rows. Apply it through Flyway. Do not run pre-claim and claim-aware binaries concurrently: old handlers can still send after an eligibility read. For this POC, stop old workers, migrate/start the new version, then recover persistent work. Rolling compatibility and rollback with in-flight CLAIMED rows require a separate production rollout plan.

## Validation

See POC-3 concurrency experiment (local, unversioned record: `../experiments/poc-3-concurrency.md`). Tests use PostgreSQL 17, concurrent connections with synchronized starts, blocked target dispatch and controlled persisted lease expiry. These are correctness tests, not a multi-process failover or performance benchmark.
