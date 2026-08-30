## Purpose

This file defines how AI coding agents must operate in this repository.

Do not use this file as the source of detailed architectural decisions.

Before making changes, read the relevant documentation under `docs/`.

---

## Project Context

This project is an experimental durable scheduling platform.

Current stack:

* Java 21
* Spring Boot
* Maven
* PostgreSQL
* Flyway
* Testcontainers

The project follows Clean Architecture.

The current stage is POC and architecture discovery through implementation and benchmarks.

---

## Required Reading

Before changing code, read:

```text
docs/architecture.md
docs/poc-strategy.md
docs/testing-strategy.md
```

When changing architectural behavior, also check:

```text
docs/adr/
```

When working on performance or scalability, check:

```text
docs/operating-envelope.md
```

Do not duplicate detailed architectural decisions in this file.

---

## General Working Rules

1. Prefer simple implementations over speculative abstractions.

2. Do not introduce infrastructure without a concrete requirement or measured limitation.

3. Preserve Clean Architecture dependency direction.

4. Domain code must not depend on Spring, JPA, PostgreSQL, HTTP, AWS, or other infrastructure technologies.

5. Do not create interfaces only to satisfy a pattern. Create ports only at meaningful architectural boundaries.

6. Do not introduce premature microservices.

7. Do not optimize for hypothetical scale before a benchmark demonstrates the need.

8. Keep changes focused on the requested feature or experiment.

9. Do not mix large refactors with functional changes unless required.

10. When assumptions are necessary, make them explicit.

---

## Architecture Rules

Follow the architecture documented in:

```text
docs/architecture.md
```

At a minimum, preserve the following dependency direction:

```text
adapter
   ↓
application
   ↓
domain
```

Infrastructure must depend on application/domain abstractions, never the opposite.

---

## Distributed Systems Rules

Never assume exactly-once execution or delivery.

Current delivery semantics are documented in ADRs.

Before modifying:

* schedule versioning;
* claims;
* retries;
* leases;
* fencing;
* idempotency;
* dispatch semantics;

read the corresponding ADR.

Do not silently change concurrency or delivery semantics.

---

## Persistence Rules

PostgreSQL-specific architectural decisions are documented under:

```text
docs/adr/
```

Use Flyway for schema changes.

Do not use automatic schema update as the migration strategy.

Do not use H2 to validate PostgreSQL-specific behavior.

Use PostgreSQL with Testcontainers for persistence integration tests.

Prefer explicit SQL when database concurrency semantics are important.

---

## Time Rules

Use `java.time.Instant` for absolute timestamps unless there is a documented reason not to.

Do not spread direct calls to `Instant.now()` across business logic.

Use an injectable `Clock` or equivalent abstraction where time affects behavior.

Tests involving time must be deterministic whenever possible.

---

## Testing Rules

Every change must include appropriate tests.

Use:

* unit tests for domain/application behavior;
* integration tests for PostgreSQL behavior;
* dedicated experiments for performance and load.

Do not treat load tests as normal unit tests.

Concurrency behavior must be tested against real PostgreSQL when database locking or transaction semantics are involved.

See:

```text
docs/testing-strategy.md
```

---

## Experiment Rules

This project is benchmark-driven.

When implementing an experiment:

1. state the hypothesis;
2. define the workload;
3. define the metrics;
4. run the experiment;
5. record the result;
6. update the operating envelope;
7. make architectural changes only if evidence justifies them.

See:

```text
docs/poc-strategy.md
docs/operating-envelope.md
```

---

## Dependency Rules

Do not add major dependencies without explaining why they are needed.

Technologies such as the following require explicit justification:

```text
Kafka
RabbitMQ
Redis
Quartz
Temporal
etcd
Consul
Cassandra
ScyllaDB
MongoDB
DynamoDB
Spring WebFlux
CQRS
Event Sourcing
```

Before adding one, answer:

```text
What problem does it solve?
What evidence shows the current solution is insufficient?
What complexity does it introduce?
Is an ADR required?
```

---

## ADR Rules

Create or update an ADR when a change materially affects:

* persistence strategy;
* delivery guarantees;
* concurrency semantics;
* partitioning/sharding;
* coordination;
* retry semantics;
* target delivery;
* architecture boundaries;
* major infrastructure choices.

Do not create ADRs for minor implementation details.

ADRs live in:

```text
docs/adr/
```

Do not rewrite historical ADRs to hide previous decisions.

Create a new ADR when a decision is superseded.

---

## Code Quality

Prefer:

* explicit names;
* small cohesive classes;
* constructor injection;
* composition;
* immutable objects where useful;
* clear transaction boundaries;
* framework-independent domain logic.

Avoid:

* generic `Utils`;
* generic `Helpers`;
* god classes;
* hidden side effects;
* static mutable state;
* unnecessary inheritance;
* unnecessary reflection;
* premature abstractions.

---

## Scope Control

Do not implement future features unless requested by the current task.

For example, do not add:

* Kafka support;
* multi-region support;
* distributed sharding;
* fencing;
* retries;
* DLQ;

during an experiment that only needs a local persisted timer.

Build incrementally.

---

## When Proposing Architecture Changes

Use this format:

```text
Problem:
Evidence:
Current limitation:
Proposed change:
Added complexity:
Alternatives:
Validation plan:
ADR required: yes/no
```

Do not propose architectural complexity without evidence.

---

## Final Rule

The objective of this repository is not to build the most sophisticated scheduler possible.

The objective is to build the simplest correct architecture, measure it, understand its limits, and evolve it based on evidence.
