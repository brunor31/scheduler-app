# `ScheduleAlreadyExistsException`

## Papel

`ScheduleAlreadyExistsException` comunica a violação da identidade única `(tenantId, scheduleId)`.

## Encaixe arquitetural

O adapter PostgreSQL converte `DuplicateKeyException` nessa exceção da aplicação. O adapter web, por sua vez, converte-a em HTTP 409.

O fluxo de tradução é intencional:

```text
violação de PK PostgreSQL
  ↓
erro significativo para a aplicação
  ↓
HTTP 409 Conflict
```

Assim, detalhes de JDBC não atravessam a aplicação nem vazam no contrato HTTP.

## Limite atual

O discovery sugere que uma criação idêntica possa devolver o recurso existente. O POC-0 ainda trata qualquer duplicidade como conflito; idempotência de criação precisa de contrato e comparação de conteúdo próprios.
