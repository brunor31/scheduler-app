# `Schedule`

## Papel

`Schedule` representa a intenção persistente de executar algo em um instante futuro. É o principal objeto de domínio do POC-0.

Ele concentra os dados que precisam permanecer consistentes independentemente de HTTP, Spring ou PostgreSQL: tenant, identificador, versão, prazo, estado e timestamps.

## Regras implementadas

- `tenantId`, `scheduleId` e `targetType` não podem ser vazios;
- a versão inicial é `1` e nunca pode ser menor que `1`;
- um novo `dueAt` deve estar no futuro em relação ao instante fornecido;
- o estado inicial é `SCHEDULED`;
- o target inicial é deliberadamente `FAKE`;
- timestamps absolutos usam `Instant`.

O método fábrica `create` recebe `now` como argumento. Assim, o domínio não chama `Instant.now()` e permanece determinístico em testes.

## Encaixe arquitetural

Está na camada de domínio e não importa nenhuma classe de framework. Adapters convertem suas representações externas para `Schedule`, mas `Schedule` não conhece esses adapters.

## Limite atual

Ainda não possui operações de cancelamento, reagendamento ou retry. Esses comportamentos serão adicionados somente nos POCs correspondentes, junto com as novas transições e testes.
