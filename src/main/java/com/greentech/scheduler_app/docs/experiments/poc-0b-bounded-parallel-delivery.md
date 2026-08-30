# POC-0b — Bounded parallel delivery

## Status

Implementation and benchmark decision complete. Two non-decisive 100-schedule repetitions remain missing from a strictly complete matrix; see the results document.

## Evidence

The POC-0 baseline plateaued around 1,000 completions per second with one delivery worker. Median-run p99 completion lag reached approximately 9.20 seconds for 10,000 schedules sharing one `dueAt`.

## Hypothesis

Four bounded local workers will reduce the 10,000-schedule median-run p99 completion lag below three seconds without loss, same-version concurrent duplication, unbounded ready work or PostgreSQL connection-pool saturation.

This threshold is an experiment target, not an approved product SLO.

## Architecture under test

```text
DelayQueue
    ↓ one deadline coordinator
bounded ArrayBlockingQueue
    ↓
1, 2, 4 or 8 delivery workers
```

Queue capacity remains 1,000 for the first comparison. HikariCP settings remain unchanged so worker count is the principal independent variable.

## Correctness gates

Before performance interpretation:

- every creation must return HTTP `201`;
- every schedule must reach `DELIVERED`;
- the same `(tenantId, scheduleId, version)` must not execute concurrently in one process;
- delivery queue size must never exceed configured capacity;
- no Hikari pending-connection backlog may remain after the burst;
- no timer or delivery worker error may appear in logs.

## Startup per worker count

Restart the application for each worker-count series:

```bash
export SCHEDULER_TIMER_DELIVERY_WORKERS=4
export SCHEDULER_TIMER_DELIVERY_QUEUE_CAPACITY=1000
export SPRING_APPLICATION_JSON='{"logging.level.com.greentech.scheduler_app.adapter.out.target.FakeScheduleTarget":"WARN"}'
./mvnw spring-boot:run
```

Use the same exported variables in the terminal that invokes the harness so they are recorded in `metadata.properties`.

## Workload matrix

Run three valid repetitions for each combination:

| Workers | Same-instant schedules | Creation concurrency | Lead time |
|---:|---:|---:|---:|
| 1, 2, 4, 8 | 100 | 16 | 60 s |
| 1, 2, 4, 8 | 1,000 | 32 | 120 s |
| 1, 2, 4, 8 | 5,000 | 32 | 180 s |
| 1, 2, 4, 8 | 10,000 | 32 | 300 s |

Example:

```bash
RUN_NOTES='POC-0b; workers=4; 10k; repetition=1' \
  ./scripts/experiments/poc0-burst.sh 10000 32 300
```

The existing one-worker POC-0 results are the historical baseline. Re-running one worker with the new coordinator/queue split is still required to detect overhead introduced by the refactor.

## Metrics

- p50, p95, p99 and p99.9 completion lag;
- maximum completion lag;
- drain span and completions per second;
- timer queue and bounded delivery queue over time;
- deliveries in progress;
- active schedule-version keys;
- Hikari active and pending connections;
- process CPU and heap;
- PostgreSQL container CPU and memory snapshots;
- delivery count and errors.

## Decision

Choose the smallest worker count that meets the experiment target without persistent connection backlog or material resource instability. Stop increasing concurrency when throughput gain becomes marginal or PostgreSQL pressure grows disproportionately.

Do not change HikariCP size, queue capacity and worker count in the same first comparison. Any later tuning must be a separate controlled experiment.

### Recorded decision

Four workers are selected for POC-1. For 10,000 same-instant schedules, they produced a median p99 completion lag of 2,579.893 ms and median throughput of 3,849.49 deliveries/s. This met the target with zero sampled Hikari pending connections. Eight workers were faster but more variable and are not required to meet the hypothesis.

Full evidence: [POC-0b bounded parallel results](poc-0b-bounded-parallel-results-2026-08-29.md).

## Smoke validation

A non-baseline smoke run validated the complete path with four workers and an isolated temporary PostgreSQL database:

| Schedules | Workers | Accepted | Delivered | Drain span | Delivered/s | p99 completion lag | Maximum lag |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 4 | 100 | 100 | 0.079 s | 1,257.88 | 93.085 ms | 93.800 ms |

The run also confirmed worker-count metadata and the runtime-series artifact. This single smoke result is not part of the operating envelope and must not be compared as if it were a three-repetition benchmark.

## Out of scope

- multiple application instances;
- distributed claim or ownership;
- leases and fencing;
- retries;
- remote target behavior;
- cancellation and rescheduling.
