# POC Strategy

## Purpose

This document defines how the durable scheduler POC evolves.

The POC is experiment-driven.

The objective is not to build the final production system immediately.

The objective is to:

- implement the simplest correct architecture;
- measure it;
- identify limitations;
- evolve only when evidence justifies the next step.

---

## Core Method

Each experiment follows:

```text
Hypothesis
    ↓
Minimal implementation
    ↓
Controlled workload
    ↓
Measurement
    ↓
Result
    ↓
Decision
```

Every meaningful experiment should answer a specific architectural question.

---

## Experiment Template

For each experiment, record:

### Hypothesis

What do we expect to be true?

### Setup

What architecture and configuration are being tested?

### Workload

Examples:

```text
schedules created per second
active schedules
same-second burst
target latency
cancel percentage
reschedule percentage
```

### Metrics

What determines success or failure?

### Result

Observed measurements.

### Conclusion

What did the result teach us?

### Decision

Keep the design, tune it, or change it?

---

# POC-0 — Local Durable Timer

## Goal

Prove the simplest scheduling loop.

```text
Create schedule
      ↓
Persist PostgreSQL
      ↓
Load upcoming timer
      ↓
Insert into local priority queue
      ↓
Wait until dueAt
      ↓
Execute fake target
      ↓
Record actual execution time
```

## Questions

- Can a persisted timer be executed reliably?
- What is the baseline timing precision?
- What overhead exists with no network target?

## Required Features

- create schedule;
- persist `dueAt`;
- local min-heap;
- fake/in-memory target;
- timing metrics.

## Out of Scope

- cancel;
- reschedule;
- retries;
- multiple instances;
- leases;
- Kafka;
- RabbitMQ;
- distributed coordination.

---

# POC-1 — Restart Recovery

## Goal

Prove that timers survive process loss.

Scenario:

```text
create future schedules
↓
stop application
↓
wait
↓
restart application
↓
reload schedules
↓
execute eligible schedules
```

## Questions

- Can the local timer queue be reconstructed?
- How quickly does recovery happen?
- How are overdue schedules handled?

## Metrics

- recovery time;
- number of missed schedules;
- execution lag after restart.

---

# POC-2 — Cancel and Reschedule

## Goal

Prove correctness when schedule state changes.

Scenarios:

```text
create → cancel
create → reschedule
create → reschedule → reschedule
```

## Questions

- Can a cancelled timer be prevented from executing?
- Can stale timer versions be safely ignored?
- Is lazy invalidation sufficient?

## Required Behavior

A stale in-memory timer must never cause stale execution.

---

# POC-3 — Concurrency

## Goal

Prove correctness under competing operations.

Scenarios:

```text
cancel vs claim
update vs claim
update vs update
cancel vs update
worker A vs worker B
```

## Questions

- Are conditional updates sufficient?
- Do transaction boundaries behave as expected?
- Where are locks actually required?

## Tools

Use real PostgreSQL through Testcontainers.

Do not simulate PostgreSQL locking semantics with H2.

---

# POC-4 — Multiple Instances

## Goal

Determine how multiple scheduler instances coordinate.

Start with the simplest viable mechanism.

```text
Instance A
Instance B
     ↓
same PostgreSQL
```

Test concurrent timer loading and claiming.

## Questions

- Can duplicate execution be controlled with conditional claims?
- Is ownership required?
- At what point do leases become useful?
- Is `SKIP LOCKED` useful for the workload?

Do not introduce shard ownership before this experiment demonstrates the need.

---

# POC-5 — Retry and Failure

## Goal

Prove behavior when delivery does not succeed cleanly.

Target outcomes:

```text
SUCCESS
RETRYABLE
PERMANENT_FAILURE
UNKNOWN
```

Scenarios:

- timeout;
- 5xx;
- slow target;
- process crash after target success but before delivery confirmation is persisted.

## Questions

- Is retry state durable?
- Is the idempotency contract sufficient?
- What happens after an unknown outcome?

---

# POC-6 — Load and Burst

## Goal

Discover the operating envelope.

Possible initial matrix:

```text
100 schedules/s
1,000 schedules/s
10,000 schedules/s
```

Burst scenarios:

```text
1,000 due in the same second
5,000 due in the same second
10,000 due in the same second
50,000 due in the same second
```

These values are experiment inputs, not product requirements.

---

## Metrics

Measure at least:

```text
create throughput
execution throughput

p50 schedule lag
p95 schedule lag
p99 schedule lag
p99.9 schedule lag

database CPU
database latency

application CPU
application memory

local timer queue size
```

---

# Workload Profiles

The POC should evaluate workload classes rather than waiting for every possible business use case.

## Short Distributed Timers

```text
create
↓
wait 5-30 minutes
↓
execute
```

Useful for queue/estimate update scenarios.

---

## Concentrated Appointments

Schedules cluster around common clock boundaries.

```text
09:00
09:30
10:00
10:30
```

The purpose is to measure same-second concentration.

---

## Extreme Burst

Artificial stress workload:

```text
100,000 schedules
same dueAt
```

Useful for finding architectural limits.

---

## High Cancellation

```text
100,000 created
70,000 cancelled
```

Measures stale timer handling and write amplification.

---

## High Reschedule

```text
create
↓
update
↓
update
↓
update
↓
only final version executes
```

---

## Target Degradation

```text
10,000 due schedules
↓
target throughput collapses
↓
backlog grows
```

Useful later for backpressure experiments.

---

# Operating Envelope

The POC should progressively populate:

```text
docs/operating-envelope.md
```

Important dimensions:

```text
create TPS
update TPS
cancel TPS
due schedules/s
same-second burst
active schedules
scheduling precision
recovery time
target latency tolerance
retry amplification
```

Never describe untested values as established capability.

---

# When to Change Architecture

Architectural changes should follow evidence.

Example:

```text
Problem:
Database polling consumes excessive CPU.

Evidence:
Benchmark shows DB CPU > 80% at workload X.

Current limitation:
Look-ahead query frequency scales poorly.

Proposed change:
Introduce wake-up signaling.

Validation:
Repeat benchmark and compare.
```

Do not add infrastructure because it could theoretically improve performance.

---

# Success Criteria

The POC succeeds if it produces useful evidence.

A failed benchmark can still be a successful experiment if it identifies:

- a bottleneck;
- a correctness problem;
- an architectural boundary;
- a condition where another product is a better choice.

---

# Build vs Buy Signal

The POC should help identify when a custom scheduler is not justified.

Possible future conclusions:

```text
If minute-level precision is acceptable,
a managed scheduler may be preferable.

If strong cancel/reschedule semantics and
sub-second-ish precision are required,
the custom architecture may provide value.
```

These remain hypotheses until validated.

---

# Rule

Do not attempt to complete the entire scheduler before running the first useful experiment.

Implement one uncertainty at a time.
