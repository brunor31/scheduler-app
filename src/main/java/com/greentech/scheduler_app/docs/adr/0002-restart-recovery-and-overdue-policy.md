# ADR-0002 — Restart recovery and overdue schedule policy

## Status

Accepted for POC-1; partially superseded by ADR-0004, ADR-0005 and ADR-0008. PostgreSQL authority, rebuildable memory and reconciliation remain active. Current recovery includes SCHEDULED, due RETRY_WAIT and expired CLAIMED rows; maximum age and optional lateness can terminalize overdue work. The historical unconditional-overdue policy, SCHEDULED-only rule, 60-second interval and single 10,000-row batch below are no longer the current contract.

## Context

The local `DelayQueue` is intentionally disposable. Abrupt process loss removes every in-memory timer while PostgreSQL retains schedules whose persistent state is `SCHEDULED`.

Recovery must define both how the local queue is reconstructed and what happens to schedules whose `dueAt` passed while no scheduler process was available. Silently discarding those rows would violate the durable scheduling intent, while a maximum-lateness policy has not yet been supplied by the product.

POC-1 tested abrupt process loss with schedules still in the future and with schedules already overdue at restart. PostgreSQL remained available throughout the application outage.

## Decision

PostgreSQL remains the source of truth. On `ApplicationReadyEvent`, and periodically as a safety net, each scheduler instance queries persistent `SCHEDULED` rows where:

```text
dueAt <= now + lookAhead
```

The query intentionally has no lower time bound. Consequently, every persisted overdue schedule remains eligible regardless of how long ago its `dueAt` passed.

Eligible rows are ordered by `dueAt`, `tenantId` and `scheduleId`, then loaded into the local timer in configurable batches. The current defaults are:

```properties
scheduler.timer.look-ahead=15m
scheduler.timer.reconciliation-interval=60s
scheduler.timer.recovery-batch-size=10000
```

An overdue row receives zero local delay and proceeds through the same bounded delivery path as an on-time row. The persistent state remains `SCHEDULED` until target dispatch succeeds and the conditional transition to `DELIVERED` is stored.

The local key `(tenantId, scheduleId, version)` stays active while the entry is queued or executing, preventing reconciliation from enqueuing the same version again inside one process.

POC-1 recovery timing is measured from process restart. A health-check timestamp is diagnostic only because `ApplicationReadyEvent` processing may dispatch schedules before an external poll observes readiness.

## Consequences

### Positive

- confirmed schedules survive loss of all in-memory timer state;
- future schedules regain their original deadline after restart;
- overdue schedules are not silently lost or expired;
- the same recovery query repairs missed local publication and lost wake-ups;
- bounded batches and the existing delivery queue prevent a recovery query from materializing an unlimited ready-delivery buffer at once.

### Negative

- recovery can create an immediate delivery burst after a long outage;
- completion lag includes unavoidable downtime and can exceed the recovery-duration objective;
- a batch limit means a backlog larger than the limit may require later reconciliation cycles;
- repeatedly failing old rows can occupy the ordered batch and delay newer rows;
- there is no product-configurable `maxLateness` or discard policy yet.

## Invariants and boundaries

- only rows still persisted as `SCHEDULED` are recovered;
- PostgreSQL state and version are revalidated before dispatch;
- recovery does not change the at-least-once delivery contract;
- database final state cannot by itself prove absence of an external duplicate side effect;
- local active-key deduplication does not provide coordination between application instances;
- this decision adds no claim, lease, fencing token or multi-instance safety;
- the batch size of 10,000 is a configurable POC safety bound, not a proven production optimum.

## Alternatives considered

### Discard every overdue schedule

Rejected because it loses confirmed scheduling intent and no product requirement currently authorizes expiration after downtime.

### Apply a fixed maximum-lateness threshold

Deferred until the product defines `misfirePolicy` and `maxLateness`. Choosing a threshold inside infrastructure would silently introduce a business rule.

### Load only future schedules

Rejected because schedules that became overdue during process loss would remain permanently stranded in `SCHEDULED`.

### Drain every eligible row in one unbounded query

Rejected because outage backlog size is unknown and unbounded loading could create excessive database, heap and delivery pressure.

### Continuously paginate until the backlog is empty

Deferred. It could reduce the delay between batches larger than 10,000, but requires an explicit fairness, retry and backpressure design to prevent recovery storms.

## Evidence

The POC-1 restart and recovery experiment (local, unversioned record: `../experiments/poc-1-restart-recovery.md`) recovered workloads from 10 through 10,000 schedules per scenario. Across the four valid local runs, all 22,220 schedules were delivered, no row remained `SCHEDULED`, and no duplicate schedule ID was observed in fake-target logs.

For the valid 10,000-schedule overdue workload, p99 delivery measured from process restart was 6,826.711 ms against the provisional 10-second recovery target. These results apply only to the recorded local environment, four delivery workers, PostgreSQL 17 and the synchronous fake target.
