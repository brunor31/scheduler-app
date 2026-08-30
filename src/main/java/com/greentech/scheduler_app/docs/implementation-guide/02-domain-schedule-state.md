# `ScheduleState`

## Papel

`ScheduleState` define os estados válidos do ciclo de vida implementado no POC-0.

```text
SCHEDULED → DELIVERED
```

`SCHEDULED` significa que o registro continua elegível para execução. `DELIVERED` significa que o target fake foi executado e a confirmação foi persistida.

## Encaixe arquitetural

O enum pertence ao domínio porque o estado do schedule é uma regra de negócio, não uma decisão de banco ou API. A migração Flyway replica esses valores em uma constraint, mas o banco não é a origem conceitual da máquina de estados.

## Por que existem apenas dois estados

O modelo maior do discovery inclui `CLAIMED`, `DISPATCHING`, `RETRY_WAIT`, `FAILED` e outros. Introduzi-los agora faria o POC-0 carregar semânticas que ainda não foram implementadas ou testadas.

Os próximos estados devem surgir junto com os experimentos que precisam deles, preservando o princípio de evolução baseada em evidência.
