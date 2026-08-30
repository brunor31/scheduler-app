# `ScheduleController`

## Papel

`ScheduleController` é o adapter de entrada HTTP do POC-0. Ele expõe:

```text
POST /v1/tenants/{tenantId}/schedules
```

O request contém `scheduleId`, `dueAt` e um payload JSON genérico.

## Responsabilidades

- desserializar e validar campos obrigatórios;
- serializar o payload para armazenamento JSONB;
- construir `CreateScheduleCommand`;
- chamar `CreateScheduleUseCase`;
- devolver HTTP 201, `Location`, `ETag` da versão e a representação criada.

## Contrato OpenAPI

As anotações `@Operation`, `@ApiResponses`, `@Parameter` e `@Schema` descrevem o contrato no limite HTTP. Elas documentam:

- os campos e exemplos do request e da resposta;
- o parâmetro `tenantId`;
- a resposta `201`, incluindo `Location` e `ETag`;
- os erros `400` e `409` no formato `ProblemDetail`.

Essas anotações pertencem ao adapter web. Os comandos, serviços e o domínio continuam independentes do OpenAPI.

## Encaixe arquitetural

O controller conhece a porta de entrada da aplicação, mas não conhece o repositório JDBC ou o timer. Nenhuma regra de estado ou de prazo é decidida aqui.

A validação no controller protege o contrato HTTP; `Schedule` repete as invariantes importantes porque o domínio também pode ser chamado por outros adapters.

## Limite atual

Somente criação foi exposta. GET, update, cancelamento e listagem pertencem às próximas etapas.
