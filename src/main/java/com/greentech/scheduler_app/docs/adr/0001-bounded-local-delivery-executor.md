# ADR-0001 — Bounded local delivery executor

## Status

Accepted for POC-0b.

## Context

The POC-0 same-second burst baseline delivered every schedule, but one local worker plateaued around 1,000 completions per second. Median-run p99 completion lag grew from approximately 1.06 seconds for 1,000 schedules to 9.20 seconds for 10,000 schedules.

The `DelayQueue` made timers eligible on time. The measured bottleneck was the serial path after expiration:

```text
PostgreSQL read → fake dispatch → conditional PostgreSQL update
```

POC-0b must test whether bounded local parallelism reduces tail latency before introducing distributed coordination or messaging.

## Decision

Keep one coordinator consuming the `DelayQueue` and hand expired entries to a bounded `ArrayBlockingQueue`. A configurable fixed number of long-lived delivery workers consumes that queue.

Defaults preserve the POC-0 baseline:

```properties
scheduler.timer.delivery-workers=1
scheduler.timer.delivery-queue-capacity=1000
```

The worker experiment will compare 1, 2, 4 and 8 workers without changing the timer structure.

The key `(tenantId, scheduleId, version)` remains active from timer publication until delivery handling finishes. Reconciliation cannot enqueue the same version while it is queued, waiting for a delivery slot or executing.

When the bounded delivery queue is full, the timer coordinator blocks on `put`. This is intentional local backpressure. It prevents unbounded ready-task accumulation while leaving remaining expired entries in the `DelayQueue`.

## Consequences

### Positive

- parallelism becomes measurable and configurable;
- ready delivery work is bounded;
- PostgreSQL pressure can be increased incrementally;
- the same-version local duplication window is closed;
- the POC-0 single-worker behavior remains available as the control.

### Negative

- execution order among schedules with the same deadline is no longer deterministic;
- more workers can increase database contention and exhaust the connection pool;
- lifecycle and observability become more complex;
- backpressure moves waiting work back toward the `DelayQueue`.

## Invariants

- PostgreSQL remains the source of truth;
- every worker revalidates persisted state and version through the application use case;
- no unbounded executor queue is allowed;
- no local key may execute concurrently with the same version;
- stopping the process may discard local queues, but persistent `SCHEDULED` rows remain recoverable;
- delivery semantics remain `at-least-once`;
- this decision does not add distributed claims, leases, fencing or multi-instance safety.

## Alternatives considered

### Unbounded fixed thread pool

Rejected because `Executors.newFixedThreadPool` queues submitted tasks without a capacity bound, hiding overload in heap growth.

### Caller-runs rejection policy

Rejected because the coordinator would become an additional delivery worker and invalidate the configured worker-count comparison.

### Kafka or another broker

Rejected because the measured limitation is local serial execution and no evidence yet requires external messaging.

### Timer wheel

Rejected because the baseline did not identify timer lookup or deadline waiting as the bottleneck.

## Validation

- unit tests for configured maximum concurrency;
- unit test for bounded ready queue/backpressure;
- unit test for same-version deduplication while in flight;
- full correctness suite against PostgreSQL;
- repeat the same 100, 1k, 5k and 10k burst matrix with 1, 2, 4 and 8 workers;
- compare lag, throughput, connection-pool pressure, CPU, memory and delivery correctness.
