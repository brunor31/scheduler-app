# `JdbcScheduleRepositoryIntegrationTests`

## Papel

Este teste valida o adapter JDBC contra PostgreSQL real.

## Comportamentos cobertos

- inserção do domínio no schema migrado;
- reconstrução do `Schedule` a partir do `ResultSet`;
- equivalência semântica do payload JSONB;
- primeira transição condicional para `DELIVERED` bem-sucedida;
- repetição da mesma transição rejeitada;
- persistência de `deliveredAt`.

## Por que comparar JSON semanticamente

PostgreSQL normaliza a apresentação textual de `jsonb`. Por exemplo, espaços podem mudar sem alterar o valor. O teste usa `ObjectMapper.readTree` para comparar a árvore JSON em vez da string.

## Encaixe na estratégia

É um teste de integração de persistência. Ele valida SQL, driver, tipos temporais, Flyway e constraints reais, exatamente onde mocks ou H2 seriam insuficientes.
