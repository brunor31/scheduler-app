# POC-0 — Local Durable Timer

## Status

Implementation foundation and same-second burst baseline complete. Distributed due-time precision remains unmeasured.

## Hypothesis

A one-time schedule persisted in PostgreSQL can be loaded into a local min-heap, dispatched to an in-memory target after `dueAt`, and marked as delivered without relying on database polling for the precise deadline.

## Setup

- Java 21 target;
- Spring Boot;
- PostgreSQL 17;
- Flyway-managed schema;
- local `DelayQueue` using a monotonic deadline;
- fake target adapter;
- 15-minute look-ahead window;
- 60-second reconciliation interval.

The look-ahead and reconciliation values are experiment defaults, not established production settings.

## Workload

The current automated correctness flow creates one schedule due approximately 750 milliseconds in the future and waits up to five seconds for delivery.

The same-second burst workload is defined in `poc-0-burst-benchmark.md` and its initial matrix has been executed. Distributed due-time measurements remain pending and must not introduce cancellation, retries, or multiple instances.

## Metrics

The implementation records:

- schedule lag: dispatch start minus `dueAt`;
- fake delivery duration;
- delivered schedule count;
- local timer queue size.

The future benchmark must report at least p50, p95, p99, and p99.9 schedule lag.

## Result

The correctness suite passed against real PostgreSQL 17:

- Flyway migrated an empty schema;
- the API persisted a schedule before publishing its timer;
- the local timer executed the fake target after `dueAt`;
- the persisted state changed conditionally from `SCHEDULED` to `DELIVERED`;
- all seven automated tests passed.

No throughput or timing capability can be inferred from this correctness run.

The subsequent burst experiment completed three valid repetitions for 100, 1,000, 5,000 and 10,000 schedules at one shared `dueAt`. All valid requests were delivered. For bursts of 1,000 or more, observed completion throughput stabilized around 1,000 schedules per second. The median-run p99 completion lag was approximately 1.06 seconds at 1,000 and 9.20 seconds at 10,000.

One additional 5,000-schedule run was interrupted by 34 seconds of confirmed macOS idle sleep and is not part of the continuous-runtime throughput baseline. See `poc-0-burst-results-2026-08-29.md`.

## Conclusion

The minimal architecture is correct and establishes a local serial delivery baseline. It drains large bursts without loss, but completion lag grows with burst size because one worker performs every delivery sequentially. The host-sleep event also produced a concrete overdue-recovery scenario for POC-1.

## Decision

Record the required business scheduling-lag objective. The bounded POC-0b implementation is ready to compare worker counts 1, 2, 4 and 8. If p99 near or below one second is required for bursts of 1,000 or more, use that comparison before proceeding. Otherwise keep the single worker and proceed to POC-1 restart/recovery. Do not introduce distributed coordination in either step.
