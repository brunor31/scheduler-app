# `HandleDueScheduleUseCase`

## Papel

`HandleDueScheduleUseCase` representa a entrada usada pelo motor de timer quando um item atinge seu deadline local.

Ele recebe apenas `tenantId`, `scheduleId` e `version`. O payload não fica na fila local; o estado autoritativo é recarregado do PostgreSQL antes da entrega.

## Encaixe arquitetural

O `LocalTimerScheduler` é um adapter técnico e depende desta porta de entrada. A decisão sobre validar estado, executar o target e persistir o resultado fica em `HandleDueScheduleService`.

Isso separa duas perguntas:

```text
Timer adapter: chegou o momento local?
Application service: este schedule persistido ainda pode executar?
```

## Preparação para versões futuras

O parâmetro `version` já permite rejeitar uma entrada obsoleta. Cancelamento e reagendamento ainda não existem, mas o limite arquitetural para stale timer já está explícito.
