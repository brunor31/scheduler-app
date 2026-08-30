# POC-0 — Same-second burst benchmark

## Status

Protocol and harness ready. No measured result has been recorded yet.

## Question

How does the current single-worker timer behave when many schedules share exactly the same `dueAt`?

## Hypothesis

The `DelayQueue` will make all expired entries eligible promptly, but the serial execution path will produce increasing completion lag as burst size grows.

## Architecture under test

Do not change the scheduler before the baseline:

- one Spring Boot process;
- one `local-timer-engine` worker;
- PostgreSQL 17;
- `DelayQueue` timer;
- synchronous `FakeScheduleTarget`;
- read-before-dispatch and conditional `DELIVERED` update;
- default HikariCP and timer configuration.

Disable the per-delivery INFO log for controlled runs so terminal output does not dominate the measurement. Record that choice with the result.

## Metric semantics

The SQL report calculates:

```text
completion lag = delivered_at - due_at
```

This includes timer delay, PostgreSQL read, fake dispatch and conditional PostgreSQL update. It is a conservative end-to-end completion measure and is not identical to the in-process `scheduler.schedule.lag`, which stops at dispatch start.

Every run uses a unique schedule prefix, so SQL results do not include earlier manual or benchmark schedules. Actuator snapshots are also archived, but they are cumulative for the application process and must be interpreted accordingly.

## Environment record

Before the first measured run, record:

```bash
java -version
docker version
curl -s http://localhost:8080/actuator/health
```

Also record:

- machine model, CPU and memory;
- Docker/Colima CPU and memory limits;
- JDK distribution and version;
- PostgreSQL image;
- application commit or source snapshot;
- datasource pool and timer settings;
- whether per-delivery logging was disabled;
- other significant workload running on the machine.

## Controlled application startup

Use the datasource configuration from `local-development.md`. To suppress the fake target INFO line during the benchmark:

```bash
export SPRING_APPLICATION_JSON='{"logging.level.com.greentech.scheduler_app.adapter.out.target.FakeScheduleTarget":"WARN"}'
./mvnw spring-boot:run
```

Restart the application before a new comparison series so cumulative application metrics have a known boundary. Confirm that no old `SCHEDULED` rows are waiting to expire during the experiment.

## Harness

Invocation:

```bash
./scripts/experiments/poc0-burst.sh <count> <creation-concurrency> <lead-seconds>
```

Example warm-up:

```bash
./scripts/experiments/poc0-burst.sh 100 16 60
```

The script:

1. verifies application and PostgreSQL health;
2. prevents macOS idle sleep with `caffeinate` for the lifetime of the run;
3. calculates one shared future `dueAt`;
4. creates synthetic schedules over HTTP with bounded client concurrency;
5. requires every response to be HTTP `201`;
6. rejects a run if loading finishes with fewer than ten seconds before `dueAt`;
7. polls PostgreSQL until all schedules are `DELIVERED` or timeout;
8. writes the scoped CSV report and Actuator snapshots under `target/experiments/poc0/<run-id>/`.

## Initial workload matrix

Run sequentially, increasing the lead time if creation cannot finish safely:

| Run | Schedules | Creation concurrency | Initial lead time |
|---|---:|---:|---:|
| warm-up | 100 | 16 | 60 s |
| baseline | 100 | 16 | 60 s |
| burst-1k | 1,000 | 32 | 120 s |
| burst-5k | 5,000 | 32 | 180 s |
| burst-10k | 10,000 | 32 | 300 s |

Example:

```bash
./scripts/experiments/poc0-burst.sh 1000 32 120
```

Do not start the next run until the previous one is fully delivered. Repeat measured points at least three times before treating their percentiles as representative.

## Recorded outputs

Each run directory contains:

- `metadata.properties`: workload identity and configuration;
- `environment.txt`: host, Java, Docker and PostgreSQL image snapshot;
- `responses.csv`: sequence and HTTP status of every creation;
- `report.csv`: scoped completion-lag percentiles and throughput;
- `timer-queue-after-load.json`: queue size snapshot;
- Actuator snapshots for lag, delivery duration, delivered count, CPU and heap;
- PostgreSQL container resource snapshots after loading and after delivery.

Use `RUN_NOTES` to persist experimental details that cannot be detected automatically:

```bash
RUN_NOTES='fake target INFO disabled; baseline repetition 1' \
  ./scripts/experiments/poc0-burst.sh 100 16 60
```

## Required result fields

Record for every measured run:

- created and delivered count;
- seconds remaining after load;
- creation span;
- delivery drain span;
- observed deliveries per second;
- p50, p95, p99 and p99.9 completion lag;
- maximum completion lag;
- application and PostgreSQL resource observations;
- errors, timeouts or environmental interference.

## Decision rule

Define the acceptable business lag before interpreting the benchmark.

If the current worker meets that objective, keep the simple design and proceed to POC-1 restart recovery. If it does not, create a POC-0b comparison using a bounded executor and repeat exactly the same matrix with worker counts such as 1, 2, 4 and 8.

Do not introduce unbounded threads, distributed claims, leases or messaging in this experiment.

## Harness validation

The complete harness matrix was validated end to end on 2026-08-29 local time. Each scenario has three valid repetitions. Values below are medians of those repetitions:

| Schedules | Valid repetitions | Accepted / delivered per run | Queue after load | Creation span | Drain span | Delivered/s | p50 | p95 | p99 | p99.9 | Maximum |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 3 | 100 / 100 | 100 | 0.327 s | 0.287 s | 348.21 | 204.056 ms | 295.675 ms | 301.517 ms | 302.735 ms | 302.870 ms |
| 1,000 | 3 | 1,000 / 1,000 | 1,000 | 3.105 s | 1.061 s | 942.78 | 597.526 ms | 1,019.312 ms | 1,059.679 ms | 1,068.852 ms | 1,069.838 ms |
| 5,000 | 3 | 5,000 / 5,000 | 5,000 | 15.527 s | 4.618 s | 1,082.82 | 2,345.135 ms | 4,387.764 ms | 4,582.052 ms | 4,619.116 ms | 4,623.526 ms |
| 10,000 | 3 | 10,000 / 10,000 | 10,000 | 30.724 s | 9.278 s | 1,077.80 | 4,657.904 ms | 8,805.024 ms | 9,197.872 ms | 9,279.780 ms | 9,288.154 ms |

All creation requests in the valid matrix returned HTTP `201`, every queue snapshot matched the requested workload, and every persisted schedule reached `DELIVERED`. The stable throughput near 1,000 completions per second from the 1,000-item scenario onward confirms that completion lag grows with the position in the single-worker backlog.

The minimum–maximum range and each individual run are preserved in [POC-0 burst results](poc-0-burst-results-2026-08-29.md). The earlier 100-item warm-up remains outside these medians because it only validated the first version of the harness with per-delivery INFO logging enabled.

## Environmental interruption observed

The third original 5,000-schedule run recorded a first completion about 31.6 seconds late and a maximum completion lag of 36.685 seconds. The macOS power log confirms that the host entered idle sleep at `2026-08-29 21:33:23 -03:00` and woke at `21:33:57`, crossing the shared `dueAt` of `21:33:28`.

This run is retained as interruption evidence but excluded from the timer-throughput baseline: no in-process scheduler can dispatch while its host is suspended. The event motivated the `caffeinate` guard in the harness. It also reinforces the need for the separate POC-1 restart/recovery and overdue-schedule experiment.
