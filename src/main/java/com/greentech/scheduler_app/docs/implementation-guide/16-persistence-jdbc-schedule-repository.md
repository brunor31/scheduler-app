# `JdbcScheduleRepository`

## Papel

`JdbcScheduleRepository` implementa a porta `ScheduleRepository` usando PostgreSQL e `JdbcClient`.

## Operações

- `INSERT` do schedule inicial;
- leitura pela chave `(tenant_id, schedule_id)`;
- busca ordenada dos schedules `SCHEDULED` até o horizonte;
- `UPDATE` condicional para `DELIVERED` por chave, versão e estado.

## Encaixe arquitetural

A classe está no adapter de saída. SQL, `ResultSet`, tradução de `DuplicateKeyException` e conversões PostgreSQL ficam confinados nela.

O domínio continua usando `Instant`. Na borda JDBC, o adapter converte explicitamente para `OffsetDateTime` em UTC porque o driver PostgreSQL não infere o tipo de `Instant` em parâmetros genéricos.

## Por que SQL explícito

O projeto permite JPA para CRUD simples, mas a transição condicional é parte da correção. O SQL deixa visível que somente a versão ainda `SCHEDULED` pode vencer.

## JSONB

O payload entra como texto JSON e é convertido com `CAST(... AS jsonb)`. Ao ler, PostgreSQL pode normalizar espaços; consumidores devem tratar o conteúdo semanticamente, não depender da formatação textual.
