# Architecture

## Purpose

This document describes the structural architecture of the durable scheduler POC.

It defines:

- architectural boundaries;
- dependency direction;
- responsibilities of each layer;
- placement of infrastructure-specific code;
- the intended evolution path.

Detailed architectural decisions must be recorded as ADRs under `docs/adr/`.

---

## Architectural Style

The project follows Clean Architecture.

The core rule is:

> Dependencies point inward.

The domain must remain independent from frameworks, databases, messaging systems, cloud providers, and transport technologies.

Conceptually:

```text
External Systems
      ↓
Inbound Adapters
      ↓
Application
      ↓
Domain

Application
      ↓
Output Ports
      ↓
Outbound Adapters
      ↓
Infrastructure / External Systems
```

---

## Layers

### Domain

Contains scheduling concepts and business rules.

Typical responsibilities:

- schedule identity;
- schedule version;
- due time;
- lifecycle state;
- valid state transitions;
- rescheduling rules;
- cancellation rules;
- invariants.

The domain must not depend on:

- Spring;
- JPA;
- PostgreSQL;
- HTTP;
- Kafka;
- RabbitMQ;
- AWS SDK;
- metrics libraries;
- Testcontainers.

Suggested structure:

```text
domain/
├── model/
├── valueobject/
├── service/
└── exception/
```

Do not create abstractions without a concrete domain reason.

---

### Application

Coordinates use cases.

Initial examples:

```text
CreateSchedule
GetSchedule
UpdateSchedule
CancelSchedule
```

Future examples:

```text
ClaimSchedule
DispatchSchedule
RetrySchedule
RecoverExpiredSchedule
```

The application layer may depend on the domain.

It defines ports for external dependencies.

Typical inbound ports:

```text
CreateScheduleUseCase
GetScheduleUseCase
UpdateScheduleUseCase
CancelScheduleUseCase
```

Typical outbound ports:

```text
ScheduleRepository
TargetDispatcher
WakeupPublisher
```

Time-dependent logic should use an injectable `Clock` when deterministic tests are needed.

---

### Inbound Adapters

Translate external requests into application commands.

Initial inbound adapter:

```text
REST API
```

Responsibilities:

- deserialize requests;
- perform transport-level validation;
- call application use cases;
- translate results into HTTP responses.

Controllers must not contain scheduling business rules.

API DTOs must not be reused as domain entities.

Suggested package:

```text
adapter/in/web/
```

---

### Outbound Adapters

Implement application output ports.

Initial persistence adapter:

```text
PostgreSQL
```

Future target adapters may include:

```text
HTTP
SQS
Kafka
RabbitMQ
gRPC
```

Suggested structure:

```text
adapter/out/
├── persistence/
├── target/
└── timer/
```

Technology-specific behavior belongs in adapters.

---

### Infrastructure

Contains framework setup and technical bootstrapping.

Suggested structure:

```text
infrastructure/
├── config/
├── observability/
└── bootstrap/
```

Typical responsibilities:

- Spring configuration;
- bean wiring;
- datasource configuration;
- actuator configuration;
- metrics configuration;
- application startup.

Infrastructure must not contain domain rules.

---

## Initial Runtime Architecture

The first POC runs as a single Spring Boot application with PostgreSQL.

Do not split it into microservices.

Initial logical flow:

```text
Client
  ↓
REST API
  ↓
Application Use Cases
  ↓
Schedule Repository
  ↓
PostgreSQL

PostgreSQL
  ↓
Timer Loader
  ↓
Local Timer Queue
  ↓
Timer Engine
  ↓
Fake Target
```

The purpose is to measure timer behavior before introducing network latency or distributed coordination.

---

## Source of Truth

Persistent state is authoritative.

The in-memory timer structure is only an optimization.

```text
Persistent State
      ↓
Load upcoming timers
      ↓
Local timer structure
```

After restart:

```text
Local timer state = lost
Persistent state = preserved
```

The timer queue must be reconstructible from persistent state.

---

## Timer Engine

Responsible for determining when a schedule becomes eligible for execution.

Initial responsibilities:

- load upcoming schedules;
- keep near-future timers in memory;
- determine the nearest deadline;
- wait until a timer becomes due;
- initiate execution.

Preferred initial structure:

```text
PriorityQueue / min-heap
```

Do not introduce a timer wheel until an experiment demonstrates a need.

---

## Timer Loading

Avoid using PostgreSQL as a constantly polled priority queue.

Preferred direction:

```text
PostgreSQL
    ↓
load look-ahead window
    ↓
local timer queue
    ↓
dynamic wait
```

A slow reconciliation query may exist as a safety mechanism.

Wake-up signaling may be introduced later if needed.

---

## Persistence Strategy

The project may use JPA and explicit SQL together.

### JPA

Appropriate for:

- simple persistence;
- straightforward reads;
- basic CRUD.

### JDBC

Prefer explicit SQL when database semantics are part of correctness.

Examples:

- optimistic concurrency;
- conditional state transitions;
- claims;
- leases;
- fencing;
- `FOR UPDATE SKIP LOCKED`;
- recovery queries.

Do not hide correctness-critical concurrency behavior behind opaque ORM behavior.

---

## Versioning

Schedules use a monotonically increasing version.

Mutations must not silently overwrite newer versions.

Conceptually:

```sql
UPDATE schedule
SET ...
WHERE tenant_id = ?
  AND schedule_id = ?
  AND version = ?;
```

If no row is updated, the operation lost the concurrency race.

---

## Stale Timer Handling

A rescheduled schedule may leave an old timer entry in memory.

Example:

```text
Memory:
schedule-123 version 4 due 10:00

Database:
schedule-123 version 5 due 10:30
```

When version 4 reaches its deadline, execution must be rejected because it is stale.

Prefer lazy invalidation initially.

---

## Dispatch Boundary

Timer management and delivery are separate responsibilities.

The Timer Engine decides:

```text
this schedule is due
```

The Dispatcher decides:

```text
how to deliver it
```

This separation allows target integrations to evolve independently from the timer implementation.

---

## Delivery Semantics

Do not assume exactly-once delivery.

The scheduler is designed around at-least-once semantics.

Idempotency belongs to the logical delivery contract.

Detailed delivery semantics must be recorded in ADRs.

---

## Time Representation

Use UTC internally.

Prefer:

```java
java.time.Instant
```

for absolute timestamps.

Avoid spreading direct calls to `Instant.now()` across domain/application code.

Use an injectable `Clock` where time affects deterministic behavior.

---

## Transactions

Transaction boundaries must be explicit.

Avoid remote calls inside long-running database transactions.

Correctness-critical state changes should be atomic.

---

## Observability

Measurement is part of the architecture from the beginning.

Initial metrics should support:

- scheduling lag;
- dispatch lag;
- schedule creation;
- cancellation;
- rescheduling;
- timer queue size;
- active schedules;
- database latency;
- execution failures.

Do not use high-cardinality identifiers such as `scheduleId` as metric labels.

---

## Evolution Path

Expected incremental evolution:

```text
single process
↓
persistent recovery
↓
cancel/reschedule
↓
concurrent claims
↓
multiple instances
↓
lease ownership
↓
fencing
↓
wake-up signaling
↓
backpressure
↓
additional target adapters
```

Do not implement later stages until experiments require them.

---

## Non-Goals of the Initial POC

The initial POC is not intended to prove:

- multi-region active/active;
- billions of schedules;
- exactly-once delivery;
- complete security architecture;
- every target adapter;
- recurring schedules;
- distributed consensus.
