# `poc0-burst-report.sql`

## Papel

O relatório transforma os timestamps persistidos de uma única execução em métricas comparáveis:

- intervalo de criação;
- intervalo de drenagem;
- throughput observado;
- p50, p95, p99 e p99.9;
- atraso máximo.

## Isolamento

Os parâmetros `tenant` e `run_prefix` restringem a consulta aos identificadores sintéticos gerados pelo harness. Isso permite manter execuções anteriores no mesmo banco sem contaminar os percentis.

## Semântica da latência

```text
completion_lag_ms = delivered_at - due_at
```

`delivered_at` é persistido depois que o target fake retorna. Portanto, essa métrica inclui o ciclo completo e é ligeiramente maior que o `schedule lag` registrado no início do dispatch.

## Por que PostgreSQL calcula os percentis

Os dados já são autoritativos no PostgreSQL, e `percentile_cont` oferece um cálculo determinístico sem introduzir biblioteca de benchmark na aplicação. O SQL pertence ao experimento e não participa do caminho de produção.

## Limite

O throughput usa o intervalo entre a primeira e a última conclusão. Para apenas um item o denominador é zero e o resultado fica nulo; a matriz proposta possui pelo menos cem schedules.
