# Testing Strategy

## Purpose

This document defines the testing strategy for the durable scheduler.

The project contains both correctness-sensitive distributed-system behavior and performance experiments.

These require different test types.

---

## Testing Principles

1. Test behavior at the lowest useful level.
2. Use real PostgreSQL when PostgreSQL semantics matter.
3. Keep domain tests independent from Spring.
4. Make time deterministic whenever possible.
5. Treat concurrency as a first-class correctness concern.
6. Keep performance experiments separate from the normal test suite.
7. Do not use mocks to simulate behavior that can be cheaply tested against real infrastructure.

---

# Unit Tests

Cover domain and application behavior that does not require infrastructure.

Examples:

- schedule creation validation;
- state transitions;
- cancellation rules;
- rescheduling;
- version increments;
- stale version rejection;
- retry calculation;
- idempotency key generation;
- target classification rules.

Pure domain tests should not start a Spring context.

Example:

```text
Given
schedule version 3 is SCHEDULED

When
it is rescheduled

Then
version becomes 4
and dueAt changes
```

---

# Time-Based Tests

Avoid relying on the machine clock where possible.

Prefer:

```java
Clock.fixed(...)
```

or a controllable test clock.

Avoid tests like:

```text
sleep 5 seconds
assert something happened
```

when deterministic testing is possible.

Real timing measurements belong to integration or experiment suites.

---

# Persistence Integration Tests

Use PostgreSQL via Testcontainers.

Do not use H2 for correctness-sensitive database tests.

Test:

- Flyway migrations;
- inserts;
- reads;
- conditional updates;
- optimistic concurrency;
- timestamp persistence;
- relevant indexes;
- locking behavior;
- transaction semantics.

---

# Flyway Tests

Integration tests should prove:

```text
empty PostgreSQL
↓
Flyway migrations
↓
valid schema
↓
repositories work
```

Never rely on Hibernate auto-update to prepare integration schemas.

---

# Concurrency Tests

Concurrency is a core risk.

Required scenarios over time include:

```text
cancel vs update
cancel vs claim
update vs claim
update vs update
worker A claim vs worker B claim
```

Example:

```text
Initial:
version = 5
state = SCHEDULED

Thread A:
cancel expectedVersion=5

Thread B:
reschedule expectedVersion=5

Expected:
exactly one mutation succeeds
the other observes a version conflict
```

Verify observable state, not assumptions about thread scheduling.

Repeat race scenarios enough times to increase the chance of exposing bugs.

---

# Timer Engine Integration Tests

Cover:

- loading upcoming schedules;
- ordering by due time;
- waiting for nearest deadline;
- executing due schedules;
- multiple due schedules;
- stale timer detection;
- queue reconstruction after restart.

Test deterministic scheduling logic separately from real-clock precision.

---

# Precision Tests

Measure:

```text
actualClaimAt - dueAt
actualDispatchStartAt - dueAt
```

Capture:

```text
p50
p95
p99
p99.9
```

Do not assert exact timestamps.

Bad assertion:

```text
execution must occur exactly at dueAt
```

Better approach:

```text
record observed lag
evaluate percentile against experiment target
```

Precision tests may be environment-sensitive and should not block every developer run.

---

# Restart and Recovery Tests

Scenarios:

```text
create future schedules
↓
terminate scheduler process
↓
restart
↓
reload
↓
execute due schedules
```

Cover:

- schedules still in the future;
- schedules that became overdue while unavailable.

Record recovery lag.

---

# Stale Version Tests

Example:

```text
version 1 due 10:00
↓
reschedule
↓
version 2 due 10:30
```

If version 1 remains in the local heap, it must not execute.

Test stale versions explicitly.

---

# Cancellation Tests

Test at least:

```text
cancel long before dueAt
cancel shortly before dueAt
cancel while timer exists in memory
cancel with stale expectedVersion
```

Later concurrency tests must cover cancellation against claim/dispatch boundaries.

---

# Multiple Instance Tests

When multi-instance behavior is introduced, use shared PostgreSQL.

Example:

```text
Instance A and B see same due schedule

Expected:
only one valid claim wins
```

Later tests may cover:

- leases;
- lease expiry;
- ownership transfer;
- fencing tokens.

Do not simulate distributed coordination purely with mocks.

---

# Failure Injection

Eventually test:

- target timeout;
- target 5xx;
- database temporarily unavailable;
- process termination;
- thread interruption;
- artificial processing delay;
- lost wake-up signal;
- crash after successful target call but before database confirmation.

Each failure test must state which invariant must remain true.

---

# Delivery Tests

Separate:

```text
Timer correctness
```

from:

```text
Target integration correctness
```

The initial timing benchmark should use a fake/in-memory target.

HTTP adapters may later use a local stub server.

Broker adapters should use real test containers when practical.

---

# API Tests

Verify transport behavior:

- create validation;
- not-found response;
- version conflict response;
- cancel response;
- malformed timestamps.

Do not duplicate all domain tests through HTTP.

Controllers should be tested mainly for contract and translation behavior.

---

# Test Pyramid

Aim for:

```text
many
domain/application unit tests

fewer
PostgreSQL integration tests

fewer
timer/recovery/concurrency integration tests

small number
load/chaos experiments
```

Do not make every test a full Spring Boot integration test.

---

# Test Categories

Suggested categories:

```text
unit
integration
concurrency
precision
load
chaos
```

Normal local/CI runs should prioritize:

```text
unit
integration
```

Expensive suites may run separately.

---

# Load Tests

Load testing is an experiment, not a correctness test.

Evaluate combinations such as:

```text
create rate
due rate
same-second burst
active schedules
cancel ratio
reschedule ratio
```

Every load test must record:

```text
test environment
application configuration
database configuration
workload
duration
result metrics
```

---

# Initial Load Matrix

Possible starting points:

```text
100 create/s
1,000 create/s
10,000 create/s
```

and:

```text
1,000 due/s
5,000 due/s
10,000 due/s
```

Same-second bursts:

```text
1k
5k
10k
50k
```

These are benchmark inputs, not production requirements.

---

# Metrics During Tests

Capture at minimum:

```text
schedule lag
dispatch lag
throughput
error rate
database latency
database CPU
application CPU
heap memory
loaded timer count
```

For recovery tests:

```text
recovery duration
overdue count
```

---

# Test Data

Do not use production-sensitive data.

Use synthetic identifiers and payloads.

Do not embed real patient, payment, customer, or tenant data in fixtures.

---

# Assertions and Flakiness

Avoid arbitrary sleeps.

Prefer:

- bounded waiting only when asynchronous behavior truly requires it;
- deterministic clock control;
- explicit synchronization in concurrency tests.

If a timing-sensitive test is flaky:

1. identify whether the behavior or the test is unstable;
2. do not just keep increasing sleep times;
3. move environment-sensitive measurements into experiment suites if appropriate.

---

# CI Expectations

The normal CI pipeline should eventually run:

```text
compile
unit tests
PostgreSQL integration tests
architecture tests if adopted
```

Performance and chaos experiments should run independently.

A pull request must not depend on noisy performance percentiles from a shared CI machine.

---

# Architecture Tests

If dependency-boundary regressions become a real problem, consider lightweight architecture tests later.

Example:

```text
domain must not depend on Spring
```

Do not add architecture-testing libraries without a concrete benefit.

---

# Test Documentation

For major experiments, record:

```text
Hypothesis
Setup
Workload
Metrics
Results
Conclusion
```

Results that change architecture should lead to:

- an update to `docs/operating-envelope.md`;
- potentially a new ADR.

---

# Initial Testing Milestones

## POC-0

- schedule domain unit tests;
- persistence integration test;
- Flyway migration test;
- timer ordering test;
- baseline precision experiment.

## POC-1

- restart/recovery;
- overdue execution.

## POC-2

- cancel;
- reschedule;
- stale version.

## POC-3

- concurrent mutations;
- concurrent claims.

## POC-4+

- multi-instance;
- leases;
- fencing;
- retry;
- failure injection;
- load.

---

# Final Rule

Correctness tests answer:

```text
Does the system behave correctly?
```

Performance experiments answer:

```text
How far can this architecture go?
```

Do not confuse the two.
