# `ScheduleTimer`

## Papel

`ScheduleTimer` é a porta de saída usada pela aplicação para publicar um schedule no mecanismo de espera local.

```java
void schedule(Schedule schedule);
```

## Encaixe arquitetural

`CreateScheduleService` depende desta abstração, enquanto `LocalTimerScheduler` fornece a implementação técnica.

A porta representa uma fronteira real: a aplicação precisa solicitar um wake-up futuro, mas não deve depender de `DelayQueue`, threads ou relógio monotônico.

## Semântica atual

Publicar no timer não torna a memória a fonte de verdade. O schedule é persistido primeiro e pode ser republicado pela reconciliação após perda do processo.

Uma implementação futura pode trocar a estrutura local sem alterar o caso de uso de criação, desde que preserve esse contrato.
