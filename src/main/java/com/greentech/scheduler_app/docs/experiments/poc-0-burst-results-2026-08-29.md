# POC-0 — Same-second burst results — 2026-08-29

## Outcome

The local single-worker scheduler delivered every accepted schedule. At 1,000 or more schedules sharing one `dueAt`, completion throughput stabilized around 1,000 schedules per second and completion lag increased approximately linearly with burst size.

No product pass/fail decision is possible until a scheduling-lag objective is defined.

## Environment

- macOS on Apple Silicon (`arm64`);
- application target Java 21; original run environments recorded Temurin 21.0.8;
- Docker Engine 29.2.1 through Colima, Linux `arm64`;
- Colima memory limit approximately 1.913 GiB;
- PostgreSQL `17-alpine`;
- one Spring Boot application instance;
- one `local-timer-engine` worker;
- synchronous fake target;
- PostgreSQL read before dispatch and conditional update after dispatch;
- 32 concurrent HTTP creation clients for 1k, 5k and 10k; 16 for 100.

The environment artifact records the Java version of the shell running the load generator. Because the generator is a Bash/curl process, the application JVM must also be recorded from its startup log; it cannot be inferred from that artifact alone.

## Valid repetitions

All values below measure completion lag as `delivered_at - due_at`.

| Schedules | Run ID | Drain | Delivered/s | p50 | p95 | p99 | p99.9 | Maximum |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 100 | `20260830T001235Z-19816` | 0.350 s | 286.11 | 212.407 ms | 354.507 ms | 362.320 ms | 364.144 ms | 364.347 ms |
| 100 | `20260830T001435Z-21685` | 0.235 s | 425.92 | 180.603 ms | 241.662 ms | 245.445 ms | 246.251 ms | 246.341 ms |
| 100 | `20260830T001623Z-23544` | 0.287 s | 348.21 | 204.056 ms | 295.675 ms | 301.517 ms | 302.735 ms | 302.870 ms |
| 1,000 | `20260830T001727Z-25282` | 1.163 s | 859.83 | 697.532 ms | 1,127.856 ms | 1,162.245 ms | 1,169.989 ms | 1,170.885 ms |
| 1,000 | `20260830T001934Z-39918` | 1.037 s | 964.70 | 561.109 ms | 1,001.124 ms | 1,036.058 ms | 1,044.207 ms | 1,045.110 ms |
| 1,000 | `20260830T002200Z-54586` | 1.061 s | 942.78 | 597.526 ms | 1,019.312 ms | 1,059.679 ms | 1,068.852 ms | 1,069.838 ms |
| 5,000 | `20260830T002408Z-69223` | 4.618 s | 1,082.82 | 2,345.135 ms | 4,387.764 ms | 4,582.052 ms | 4,619.116 ms | 4,623.526 ms |
| 5,000 | `20260830T002716Z-40673` | 5.044 s | 991.24 | 2,606.670 ms | 4,824.445 ms | 5,004.282 ms | 5,047.800 ms | 5,052.867 ms |
| 5,000 | `20260830T004015Z-7200` | 4.434 s | 1,127.59 | 2,297.615 ms | 4,245.253 ms | 4,402.639 ms | 4,439.152 ms | 4,443.653 ms |
| 10,000 | `20260830T003415Z-83153` | 9.278 s | 1,077.80 | 4,657.904 ms | 8,805.024 ms | 9,197.872 ms | 9,279.780 ms | 9,288.154 ms |
| 10,000 | `20260830T003528Z-24523` | 10.135 s | 986.66 | 5,159.562 ms | 9,652.708 ms | 10,038.784 ms | 10,131.701 ms | 10,141.927 ms |
| 10,000 | `20260830T003650Z-65463` | 9.127 s | 1,095.64 | 4,590.092 ms | 8,663.002 ms | 9,052.866 ms | 9,127.403 ms | 9,136.438 ms |

## Consolidated baseline

Values are medians of three valid repetitions. Parentheses show the observed minimum–maximum range.

| Schedules | Delivered/s | p50 completion lag | p99 completion lag | Maximum completion lag |
|---:|---:|---:|---:|---:|
| 100 | 348.21 (286.11–425.92) | 204.056 ms (180.603–212.407) | 301.517 ms (245.445–362.320) | 302.870 ms (246.341–364.347) |
| 1,000 | 942.78 (859.83–964.70) | 597.526 ms (561.109–697.532) | 1,059.679 ms (1,036.058–1,162.245) | 1,069.838 ms (1,045.110–1,170.885) |
| 5,000 | 1,082.82 (991.24–1,127.59) | 2,345.135 ms (2,297.615–2,606.670) | 4,582.052 ms (4,402.639–5,004.282) | 4,623.526 ms (4,443.653–5,052.867) |
| 10,000 | 1,077.80 (986.66–1,095.64) | 4,657.904 ms (4,590.092–5,159.562) | 9,197.872 ms (9,052.866–10,038.784) | 9,288.154 ms (9,136.438–10,141.927) |

## Interrupted run retained as evidence

Run `20260830T003028Z-12030` loaded all 5,000 timers correctly but recorded:

- first completion lag: approximately 31.641 seconds;
- p99 completion lag: 36.637 seconds;
- maximum completion lag: 36.685 seconds;
- drain span after execution resumed: 5.044 seconds.

The macOS power log shows idle sleep from `21:33:23` to `21:33:57 -03:00`, crossing the shared `dueAt` at `21:33:28`. The process could not dispatch while the host was suspended. This run is excluded from the continuous-runtime baseline but retained because it demonstrates overdue behavior after environmental suspension.

The harness now uses `caffeinate` on macOS to prevent recurrence during performance measurements.

## Interpretation

The near-constant throughput above 1,000 items indicates that the timer wait structure is not the limiting factor in this workload. The dominant path is serial handling after expiration:

```text
PostgreSQL read
  → fake dispatch
  → conditional PostgreSQL update
  → next schedule
```

At roughly 1,000 completions per second, a burst of `N` schedules naturally gives a median completion near `N / 2,000` seconds and a tail near `N / 1,000` seconds, matching the measurements.

## Decision boundary

- If the product accepts roughly ten seconds of p99 completion lag for a 10k same-instant burst, the single-worker baseline may be sufficient and POC-1 is next.
- If the product requires sub-second or low-single-second p99 for these bursts, the evidence justifies POC-0b: bounded parallel delivery with worker counts 1, 2, 4 and 8, using this exact workload for comparison.
- No result here justifies messaging, multiple instances, leases or distributed claims.
