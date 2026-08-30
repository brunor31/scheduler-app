# `HandleDueScheduleService`

## Papel

`HandleDueScheduleService` coordena o processamento de um timer que chegou ao deadline.

Antes de executar, ele recarrega o registro persistido e verifica:

- existência;
- estado `SCHEDULED`;
- mesma versão da entrada local.

Uma entrada ausente, entregue ou obsoleta é ignorada.

## Fluxo bem-sucedido

```text
buscar Schedule
  ↓
validar estado e versão
  ↓
capturar dispatchStartedAt
  ↓
executar ScheduleTarget
  ↓
marcar DELIVERED condicionalmente
  ↓
registrar métricas
```

## Encaixe arquitetural

É um serviço puro da aplicação. Depende das portas de repositório, target e métricas, além de um `Clock` injetável.

## Limite atual

Uma falha do target não possui retry persistido. Com o target fake isso não ocorre; classificação de falhas e unknown outcome pertencem ao POC-5.
