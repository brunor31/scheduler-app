# ADR-0003 — Versioned mutations and lazy timer invalidation

## Status

Accepted for POC-2; partially superseded by ADR-0004, ADR-0005 and ADR-0008. Optimistic versioning, persistent-first mutation and stale-version rejection remain active. The final execution boundary is now an atomic claim, mutable states include RETRY_WAIT, and newer local waiting entries may replace older ones as a memory optimization. Eager removal is still not the correctness mechanism. The eligibility-read race and SCHEDULED-only statements below describe the historical POC.

## Context

Cancellation and rescheduling can make an entry already held by the local `DelayQueue` obsolete. Removing an exact entry from every possible in-memory location is fragile: it couples correctness to process-local state, does not survive restart and will not coordinate multiple instances.

The API also needs a deterministic answer when two clients mutate the same schedule from the same previously observed state.

## Decision

Every accepted schedule mutation uses optimistic concurrency through the persistent, monotonically increasing `version`.

Clients submit the version they intend to change in `If-Match`. Cancellation and rescheduling are accepted only while the current persistent state is `SCHEDULED`. The repository performs a conditional update using tenant, schedule ID, expected version and expected state. A successful mutation increments the version exactly once.

The API reports:

- HTTP 404 when the tenant-scoped schedule does not exist;
- HTTP 412 when `If-Match` is not the current version;
- HTTP 409 when the current state does not allow the requested transition;
- HTTP 400 for a malformed precondition or invalid new deadline.

Cancellation persists state `CANCELLED` and `cancelledAt` and does not create another timer. Rescheduling persists the new `dueAt` and version before publishing a timer for that version.

Existing timer entries are not eagerly removed. On fire, the handler reloads PostgreSQL and dispatches only if both conditions hold:

```text
persistent state == SCHEDULED
persistent version == timer version
```

All other entries are stale and terminate without calling the delivery target. This is the selected lazy-invalidation strategy.

## Consequences

### Positive

- PostgreSQL remains the authority for mutation and delivery eligibility;
- a stale client cannot silently overwrite a newer decision;
- two mutations based on the same version cannot both commit;
- cancellation does not depend on finding an object inside the timer implementation;
- repeated rescheduling does not require coordinated deletion from process-local queues;
- restart and reconciliation naturally rebuild only the latest persisted version;
- the design remains valid when an old local entry fires after its replacement.

### Negative

- stale entries remain in memory until their original deadline and consume queue capacity;
- a schedule rescheduled far into the future can retain an old entry until that old deadline;
- rescheduling persists before local timer publication, so a process failure in between relies on reconciliation to recover the new version;
- optimistic conflicts are visible to clients and require them to reload before retrying;
- this decision does not prevent a side effect if dispatch already passed its final eligibility read before a concurrent mutation commits.

## Invariants and boundaries

- version starts at 1 and increases once per accepted mutation;
- only `SCHEDULED` schedules can be cancelled or rescheduled;
- a new `dueAt` must be in the future according to the injected application clock;
- tenant ID is part of every lookup and conditional update;
- cancelled schedules are terminal in POC-2;
- stale timer rejection precedes target dispatch when the mutation is already visible;
- lazy invalidation is process-local optimization plus persistent validation, not a claim or lock;
- concurrent dispatch versus mutation remains a POC-3 question.

## Alternatives considered

### Remove the old entry eagerly from `DelayQueue`

Rejected as the correctness mechanism because removal can race with dequeue, does not cover another process and cannot survive loss of memory state. It may be considered later only as a memory optimization.

### Overwrite without an expected version

Rejected because concurrent clients could lose each other's updates without observing a conflict.

### Reuse the same version after rescheduling

Rejected because the old and new timer entries would be indistinguishable when the old deadline fires.

### Publish the replacement timer before committing PostgreSQL

Rejected because an uncommitted or failed mutation could create an executable timer that has no authoritative persistent counterpart.

### Hold a database transaction open during external dispatch

Deferred to the concurrency POC. It increases lock duration and still does not make a non-transactional external side effect atomic with PostgreSQL.

## Evidence

The POC-2 experiment record (local, unversioned record: `../experiments/poc-2-cancel-reschedule.md`) documents the API, test scenarios and boundary. The full suite completed with 24 tests, 0 failures and 0 errors against PostgreSQL 17 through Testcontainers.
