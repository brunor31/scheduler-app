# `ScheduleMetrics`

## Papel

`ScheduleMetrics` é a porta de saída para registrar evidência operacional sem acoplar a aplicação ao Micrometer.

O contrato atual registra:

- schedule lag;
- duração da entrega.

## Encaixe arquitetural

`HandleDueScheduleService` calcula os valores usando `Instant` e `Duration`. `MicrometerScheduleMetrics` decide os nomes e tipos concretos dos medidores.

Essa separação mantém bibliotecas de observabilidade fora do domínio e da aplicação.

## Semântica

O schedule lag é calculado como `dispatchStartedAt - dueAt`, limitado a zero quando negativo. A métrica mede o comportamento observado; não prova um SLO sem uma carga e um ambiente de benchmark documentados.
