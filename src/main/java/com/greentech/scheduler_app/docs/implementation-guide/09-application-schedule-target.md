# `ScheduleTarget`

## Papel

`ScheduleTarget` é a porta de saída para executar o efeito associado a um schedule devido.

No POC-0 existe apenas `FakeScheduleTarget`. A aplicação conhece somente a operação `dispatch(Schedule)`.

## Encaixe arquitetural

Essa porta separa a correção do timer da integração com destinos. O timer decide quando; o target adapter decide como entregar.

```text
Timer Engine → Application → ScheduleTarget → Target externo
```

## Limite atual

O contrato ainda não classifica `SUCCESS`, `RETRYABLE`, `PERMANENT` ou `UNKNOWN`. Adicionar esses resultados agora anteciparia o POC de retries e semântica at-least-once.

HTTP, SQS ou outros adapters deverão implementar essa porta ou sua evolução quando houver requisitos e testes específicos.
