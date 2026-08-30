# `SchedulePocFlowIntegrationTests`

## Papel

Este teste prova o corte vertical completo do POC-0:

```text
HTTP POST
  ↓
caso de uso
  ↓
PostgreSQL
  ↓
timer local
  ↓
target fake
  ↓
estado DELIVERED
```

## Cenário

O teste cria um schedule aproximadamente 750 ms no futuro, valida HTTP 201, ETag e estado inicial, e usa espera limitada a cinco segundos até observar `DELIVERED` no banco.

## Por que usa tempo real

O comportamento assíncrono real da `DelayQueue` precisa de passagem de tempo. A espera é curta e limitada; regras determinísticas de domínio continuam cobertas por testes com relógio fixo.

## Infraestrutura

O contexto usa PostgreSQL 17 via Testcontainers e executa a migração Flyway. Assim, o fluxo não depende de H2 nem simula comportamento PostgreSQL.

## Contrato OpenAPI

O teste também consulta `/v3/api-docs` e verifica que o contrato de criação publica as respostas `201`, `400` e `409`. Isso detecta regressões como remoção acidental do endpoint OpenAPI ou perda de respostas documentadas.

## O que não prova

Um único schedule não mede percentis, throughput ou capacidade. O teste responde “o fluxo funciona?”, não “qual é o limite da arquitetura?”.
