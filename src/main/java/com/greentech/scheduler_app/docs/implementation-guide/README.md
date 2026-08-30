# Guia didático da implementação do POC-0

## Objetivo

Esta seção explica, artefato por artefato, a primeira implementação do scheduler durável. Cada capítulo corresponde a uma classe, configuração, migração ou teste alterado durante o POC-0.

O guia complementa, sem substituir:

- [Architecture](../architecture.md);
- [POC Strategy](../poc-strategy.md);
- [Testing Strategy](../testing-strategy.md);
- [POC-0 experiment record](../experiments/poc-0-local-durable-timer.md).

## Fluxo completo

```text
HTTP POST
  ↓
ScheduleController
  ↓
CreateScheduleUseCase
  ↓
CreateScheduleService
  ├─→ ScheduleRepository → PostgreSQL
  └─→ ScheduleTimer → DelayQueue
                         ↓ dueAt
                  HandleDueScheduleUseCase
                         ↓
              HandleDueScheduleService
                  ├─→ ScheduleTarget
                  ├─→ ScheduleRepository
                  └─→ ScheduleMetrics
```

O PostgreSQL é a fonte de verdade. A fila local apenas reduz o polling e fornece espera precisa dentro do processo; ela pode ser reconstruída pela reconciliação.

## Capítulos

### Domínio

1. [Schedule](01-domain-schedule.md)
2. [ScheduleState](02-domain-schedule-state.md)
3. [InvalidScheduleException](03-domain-invalid-schedule-exception.md)

### Aplicação

4. [CreateScheduleCommand](04-application-create-schedule-command.md)
5. [CreateScheduleUseCase](05-application-create-schedule-use-case.md)
6. [HandleDueScheduleUseCase](06-application-handle-due-schedule-use-case.md)
7. [ScheduleRepository](07-application-schedule-repository.md)
8. [ScheduleTimer](08-application-schedule-timer.md)
9. [ScheduleTarget](09-application-schedule-target.md)
10. [ScheduleMetrics](10-application-schedule-metrics.md)
11. [ScheduleAlreadyExistsException](11-application-schedule-already-exists-exception.md)
12. [CreateScheduleService](12-application-create-schedule-service.md)
13. [HandleDueScheduleService](13-application-handle-due-schedule-service.md)

### Adapters e infraestrutura

14. [ScheduleController](14-web-schedule-controller.md)
15. [ApiExceptionHandler](15-web-api-exception-handler.md)
16. [JdbcScheduleRepository](16-persistence-jdbc-schedule-repository.md)
17. [LocalTimerScheduler](17-timer-local-timer-scheduler.md)
18. [ScheduleRecoveryLoader](18-timer-schedule-recovery-loader.md)
19. [FakeScheduleTarget](19-target-fake-schedule-target.md)
20. [MicrometerScheduleMetrics](20-observability-micrometer-schedule-metrics.md)
21. [SchedulerConfiguration](21-infrastructure-scheduler-configuration.md)

### Banco, configuração e evidência

22. [Migração Flyway V1](22-database-flyway-v1.md)
23. [application.properties](23-configuration-application-properties.md)
24. [SchedulePocFlowIntegrationTests](24-test-poc-flow.md)
25. [JdbcScheduleRepositoryIntegrationTests](25-test-jdbc-repository.md)
26. [CreateScheduleServiceTest](26-test-create-schedule-service.md)
27. [ScheduleTest](27-test-schedule-domain.md)
28. [TestcontainersConfiguration](28-test-testcontainers-configuration.md)
29. [Registro do experimento POC-0](29-documentation-poc-0-experiment.md)
30. [OpenApiConfiguration e Springdoc](30-infrastructure-openapi.md)
31. [Harness de burst do POC-0](31-experiment-poc0-burst-script.md)
32. [Relatório SQL do burst](32-experiment-poc0-burst-report.md)
33. [Resultado do benchmark de burst](33-documentation-poc0-burst-results.md)
34. [Teste do timer paralelo limitado](34-test-local-timer-scheduler.md)

## Execução local

O roteiro para preparar PostgreSQL, iniciar a aplicação e executar a primeira carga manual está em [Desenvolvimento local e testes manuais](../local-development.md).

## Limite arquitetural atual

Este guia descreve somente o POC-0. Cancelamento, reagendamento, retries, claims distribuídos, leases, fencing e múltiplas instâncias pertencem aos experimentos posteriores e não devem ser inferidos a partir desta implementação.
