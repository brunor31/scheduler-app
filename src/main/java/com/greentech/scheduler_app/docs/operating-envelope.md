# Operating envelope

## Status

POC-0 and POC-0b same-second bursts measured on the local development environment. No production capability has been established.

This document records measured limits of the current architecture. Values must come from reproducible experiments; examples, manual observations and targets are not capabilities.

## Current architecture baseline

- one application instance;
- one local timer worker;
- PostgreSQL as source of truth;
- local `DelayQueue`;
- synchronous fake target;
- at-least-once delivery semantics.

## POC-0 same-second burst

Protocol: [POC-0 same-second burst benchmark](experiments/poc-0-burst-benchmark.md).

| Schedules at same `dueAt` | Repetitions | p50 | p95 | p99 | p99.9 | Maximum | Delivered/s | Result |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 100 | 3 | 204.056 ms | 295.675 ms | 301.517 ms | 302.735 ms | 302.870 ms | 348.21 | measured locally |
| 1,000 | 3 | 597.526 ms | 1,019.312 ms | 1,059.679 ms | 1,068.852 ms | 1,069.838 ms | 942.78 | measured locally |
| 5,000 | 3 valid + 1 interrupted | 2,345.135 ms | 4,387.764 ms | 4,582.052 ms | 4,619.116 ms | 4,623.526 ms | 1,082.82 | measured locally |
| 10,000 | 3 | 4,657.904 ms | 8,805.024 ms | 9,197.872 ms | 9,279.780 ms | 9,288.154 ms | 1,077.80 | measured locally |

Values are medians of the three valid repetitions. The valid-run ranges are recorded in [POC-0 burst results](experiments/poc-0-burst-results-2026-08-29.md).

## Established local observations

- every valid request was persisted and delivered;
- throughput plateaued around 1,000 completions per second for bursts of 1,000 or more;
- completion lag grows approximately linearly with queue position because one worker performs the PostgreSQL read, fake dispatch and conditional update serially;
- the 10,000-schedule burst had a median-run p99 completion lag of about 9.2 seconds;
- one 5,000-schedule run crossed 34 seconds of macOS idle sleep and is excluded from the continuous-runtime baseline.

These observations apply only to the recorded local Apple Silicon/Colima environment, PostgreSQL 17, fake target and single-worker implementation.

## Interpretation boundary

The POC currently has no agreed scheduling-lag objective. Benchmark numbers characterize behavior, but pass/fail requires an explicit business target. For example, the current implementation demonstrably does not keep p99 below one second for a same-instant burst of 1,000 schedules, but that becomes a failure only if the product requires that bound.

When results are added, include the environment and link to the raw run artifacts or a durable summary. Do not extrapolate beyond measured workload, hardware and configuration.

## POC-0b bounded parallel delivery

Protocol: [POC-0b bounded parallel delivery](experiments/poc-0b-bounded-parallel-delivery.md).

| Workers | 100 | 1,000 | 5,000 | 10,000 | Decision |
|---:|---|---|---|---|---|
| 1 | 165.094 ms | 1,011.973 ms | 4,559.744 ms | 8,969.746 ms | baseline |
| 2 | 114.831 ms | 618.622 ms | 2,123.327 ms | 3,733.888 ms | target not met at 10k |
| 4 | 48.261 ms* | 297.262 ms | 1,332.562 ms | 2,579.893 ms | selected: smallest count meeting target |
| 8 | 52.214 ms* | 231.446 ms | 1,017.586 ms | 2,003.207 ms | faster, but more variable at 10k |

Cells contain median p99 completion lag. Values are medians of three valid repetitions, except `*`, which has two valid repetitions because the third run was rejected by the pre-deadline validity gate. Detailed values, throughput, ranges and excluded attempts are recorded in [POC-0b results](experiments/poc-0b-bounded-parallel-results-2026-08-29.md).

The four-worker hypothesis was confirmed locally: median-run p99 for 10,000 schedules fell 71.2% from the re-baselined one-worker result and remained below the three-second experiment target. The bounded delivery queue reached its capacity of 1,000 during all 10,000-schedule runs, while sampled Hikari pending connections remained zero.
