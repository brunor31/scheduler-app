# Registro `poc-0-local-durable-timer.md`

## Papel

O registro do experimento conecta código a uma pergunta arquitetural. Ele evita que uma implementação funcional seja confundida com capacidade comprovada.

## Conteúdo registrado

- hipótese do timer durável local;
- setup usado;
- workload funcional atual;
- métricas instrumentadas;
- resultado da suíte PostgreSQL;
- conclusão e próxima decisão.

## Encaixe no processo da POC

O projeto evolui por:

```text
hipótese → implementação mínima → workload → medição → decisão
```

O documento deixa claro que a correção funcional passou, mas o benchmark de precisão ainda não foi executado. Portanto, o operating envelope não deve receber números de capacidade neste momento.

## Próxima atualização

O protocolo e o harness do burst agora estão prontos. Após sua execução, o registro deve receber ambiente, configuração, distribuição dos schedules, p50/p95/p99/p99.9 e conclusão. Mudanças arquiteturais só devem seguir se os dados mostrarem uma limitação concreta.
