# `MicrometerScheduleMetrics`

## Papel

`MicrometerScheduleMetrics` implementa a porta `ScheduleMetrics` com os medidores da aplicação Spring.

Métricas atuais:

```text
scheduler.schedules.delivered
scheduler.schedule.lag
scheduler.delivery.duration
scheduler.timer.queue.size
```

As três primeiras são registradas por este adapter. O gauge da fila é registrado pelo próprio timer, que possui a coleção observada.

## Encaixe arquitetural

Micrometer permanece na camada de adapter. A aplicação fornece `Duration` e não conhece registry, tags ou formato de exportação.

## Cardinalidade

Tenant e schedule ID não são labels. Esses identificadores teriam cardinalidade alta e degradariam o sistema de métricas; permanecem em logs.

## Interpretação

Os timers permitem construir histogramas quando o backend for configurado. Valores isolados do teste funcional não validam os percentis ou SLOs propostos no discovery.
