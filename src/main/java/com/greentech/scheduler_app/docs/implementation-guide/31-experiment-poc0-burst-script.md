# `poc0-burst.sh`

## Papel

O script é o driver reproduzível do experimento de concentração no mesmo instante. Ele cria schedules sintéticos pela API pública, em vez de inserir diretamente no banco, preservando no workload o caminho real de validação, persistência e registro no timer.

## Controles de validade

- gera um `run_id` exclusivo para evitar mistura com dados anteriores;
- limita a concorrência de criação com `xargs -P`;
- exige HTTP `201` para todos os schedules;
- aborta se a carga terminar a menos de dez segundos de `dueAt`;
- usa timeout explícito para a drenagem;
- mantém resultados parciais quando ocorre falha.
- no macOS, mantém uma assertion de atividade com `caffeinate` enquanto o experimento estiver rodando.

O lead time é essencial: se parte da carga ainda estiver sendo criada no vencimento, o teste medirá criação atrasada, e não apenas a capacidade de drenagem do timer.

A proteção contra sleep foi adicionada depois que uma rodada de 5 mil schedules atravessou 34 segundos de suspensão do notebook. A rodada foi preservada como evidência ambiental, mas não representa throughput do timer em execução contínua.

## Saídas

Os artefatos ficam em `target/experiments/poc0/<run-id>/`, que já é ignorado pelo Git. Cada execução preserva metadata, ambiente, respostas HTTP, relatório PostgreSQL, snapshots do Actuator e snapshots de recursos do container PostgreSQL.

As variáveis `SCHEDULER_TIMER_DELIVERY_WORKERS` e `SCHEDULER_TIMER_DELIVERY_QUEUE_CAPACITY` também são gravadas na metadata quando exportadas para iniciar aplicação e harness. `RUN_NOTES` permite registrar informações adicionais, como o número da repetição.

`runtime-series.csv` captura, a partir de dois segundos antes de `dueAt`, a evolução de:

- itens ainda na `DelayQueue`;
- fila de entrega;
- execuções em andamento;
- chaves ativas;
- conexões Hikari ativas e pendentes.

Essa série permite observar backpressure sem adicionar uma biblioteca de carga ao runtime.

## Encaixe arquitetural

O script fica fora de `src/test` porque load testing é um experimento, não um teste de correção executado no CI. Ele não adiciona dependências nem muda o runtime do scheduler.

## Limite

O gerador usa `curl` e mede a entrega do processo local conectado ao container PostgreSQL indicado. Ele não representa tráfego distribuído, latência de rede de produção ou um target externo.
