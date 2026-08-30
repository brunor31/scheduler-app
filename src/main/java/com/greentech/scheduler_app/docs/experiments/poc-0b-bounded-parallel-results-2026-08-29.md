# POC-0b bounded parallel delivery results — 2026-08-29

## Conclusion

The POC-0b hypothesis was confirmed in the measured local environment. Four bounded delivery workers reduced the median-run p99 completion lag for 10,000 schedules sharing one `dueAt` from 8,969.746 ms to 2,579.893 ms. This is a 71.2% reduction and meets the experimental target of less than three seconds.

Four workers are the selected configuration for the next POC because they are the smallest tested worker count that meets the target. Eight workers improved the 10,000-schedule median p99 to 2,003.207 ms, but showed substantially greater run-to-run variation.

This is an experiment decision, not a production SLO or capacity claim.

## Scope and environment

- one application instance;
- PostgreSQL 17 in the same local Apple Silicon/Colima environment as POC-0;
- synchronous fake target;
- one deadline coordinator;
- bounded delivery queue with capacity 1,000;
- 1, 2, 4 or 8 long-lived delivery workers;
- all schedules in each run shared one `dueAt`;
- HikariCP and all variables other than worker count remained unchanged.

Raw artifacts are under `target/experiments/poc0b/workers-{1,2,4,8}` and are intentionally not treated as durable repository documentation.

## Validity of the matrix

There are 46 complete benchmark reports representing 193,000 accepted and delivered schedules:

- three valid repetitions for every 1,000, 5,000 and 10,000 scenario;
- three valid repetitions for 100 schedules with one and two workers;
- two valid repetitions for 100 schedules with four and eight workers.

The missing 100-schedule repetitions completed their HTTP load with only nine seconds remaining before `dueAt`. The harness correctly rejected them because it requires at least ten seconds. Several earlier attempts with short lead times were also interrupted or rejected and are excluded.

The two-repetition 100-schedule cells are provisional. They do not affect the 10,000-schedule hypothesis decision, but a strictly complete 48-run matrix requires one more valid 100-schedule run with four workers and one with eight workers.

For the one-worker 100-schedule cell, the later complete repetition set was used. An earlier complete run preceding a rejected second repetition was excluded to avoid mixing two attempted series.

## Median results

Values are medians of three valid repetitions unless marked with `*`. A two-value median is the midpoint of the two observations.

| Workers | Schedules | Repetitions | Delivered/s | p50 | p95 | p99 | p99.9 | Maximum |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 100 | 3 | 654.42 | 118.463 ms | 161.788 ms | 165.094 ms | 165.909 ms | 165.999 ms |
| 1 | 1,000 | 3 | 985.71 | 571.715 ms | 979.798 ms | 1,011.973 ms | 1,019.491 ms | 1,020.166 ms |
| 1 | 5,000 | 3 | 1,087.32 | 2,337.196 ms | 4,388.498 ms | 4,559.744 ms | 4,604.829 ms | 4,610.002 ms |
| 1 | 10,000 | 3 | 1,105.71 | 4,569.331 ms | 8,607.068 ms | 8,969.746 ms | 9,043.056 ms | 9,051.132 ms |
| 2 | 100 | 3 | 941.36 | 83.922 ms | 112.560 ms | 114.831 ms | 115.730 ms | 115.830 ms |
| 2 | 1,000 | 3 | 1,617.89 | 340.822 ms | 593.765 ms | 618.622 ms | 623.942 ms | 624.816 ms |
| 2 | 5,000 | 3 | 2,343.00 | 1,121.981 ms | 2,041.558 ms | 2,123.327 ms | 2,141.731 ms | 2,143.458 ms |
| 2 | 10,000 | 3 | 2,656.57 | 1,961.519 ms | 3,596.643 ms | 3,733.888 ms | 3,764.901 ms | 3,768.191 ms |
| 4 | 100 | 2* | 2,226.69 | 30.002 ms | 47.014 ms | 48.261 ms | 48.596 ms | 48.633 ms |
| 4 | 1,000 | 3 | 3,454.43 | 180.306 ms | 288.102 ms | 297.262 ms | 299.506 ms | 299.858 ms |
| 4 | 5,000 | 3 | 3,742.20 | 699.068 ms | 1,283.132 ms | 1,332.562 ms | 1,343.058 ms | 1,344.339 ms |
| 4 | 10,000 | 3 | 3,849.49 | 1,361.314 ms | 2,484.470 ms | 2,579.893 ms | 2,600.966 ms | 2,603.708 ms |
| 8 | 100 | 2* | 2,643.41 | 36.062 ms | 51.226 ms | 52.214 ms | 52.420 ms | 52.444 ms |
| 8 | 1,000 | 3 | 4,517.26 | 140.390 ms | 224.129 ms | 231.446 ms | 233.427 ms | 233.478 ms |
| 8 | 5,000 | 3 | 4,899.70 | 523.450 ms | 971.741 ms | 1,017.586 ms | 1,028.138 ms | 1,029.124 ms |
| 8 | 10,000 | 3 | 4,964.40 | 972.554 ms | 1,918.482 ms | 2,003.207 ms | 2,020.441 ms | 2,022.072 ms |

## Ten-thousand-schedule comparison

| Workers | Median delivered/s | Throughput vs 1 worker | Median p99 | p99 reduction vs 1 worker | Observed p99 range |
|---:|---:|---:|---:|---:|---:|
| 1 | 1,105.71 | 1.00x | 8,969.746 ms | — | 8,941.296–9,379.232 ms |
| 2 | 2,656.57 | 2.40x | 3,733.888 ms | 58.4% | 3,730.854–3,814.884 ms |
| 4 | 3,849.49 | 3.48x | 2,579.893 ms | 71.2% | 2,551.498–2,725.199 ms |
| 8 | 4,964.40 | 4.49x | 2,003.207 ms | 77.7% | 1,878.384–2,561.543 ms |

Moving from four to eight workers increased median throughput by 29.0% and reduced median p99 by 22.4%. However, the eight-worker p99 range was 683.159 ms, compared with 173.701 ms at four workers. The result suggests diminishing and less predictable returns, not a database saturation limit.

## Correctness and backpressure evidence

- every complete report contains the requested delivered count;
- all 10,000-schedule runs reached the configured delivery-queue capacity of 1,000, demonstrating bounded backlog and coordinator backpressure;
- observed in-progress delivery reached the configured worker count;
- Hikari pending connections remained zero in every captured runtime sample;
- Hikari active connections scaled to the worker count in the large runs;
- the local same-version concurrency invariant is covered separately by `LocalTimerSchedulerTest`.

The benchmark artifacts do not contain application logs, so the “no worker error in logs” gate cannot be independently reconstructed from the result directories. It must be confirmed from the terminal log retained or observed by the operator. Database final state alone also cannot prove absence of an external duplicate invocation; the fake target log and the deterministic concurrency test provide that complementary evidence.

## Decision

Use four delivery workers and queue capacity 1,000 for POC-1 restart and recovery experiments:

```bash
export SCHEDULER_TIMER_DELIVERY_WORKERS=4
export SCHEDULER_TIMER_DELIVERY_QUEUE_CAPACITY=1000
```

Keep the application default unchanged until four workers become an explicit product/runtime configuration decision. Do not increase the Hikari pool or queue capacity based on this experiment: no pending-connection pressure was observed, and changing either would introduce a new independent variable.

The next architectural uncertainty is restart recovery, not additional local parallelism.
