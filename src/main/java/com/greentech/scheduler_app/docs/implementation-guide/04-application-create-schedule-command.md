# `CreateScheduleCommand`

## Papel

`CreateScheduleCommand` é a entrada estável do caso de uso de criação. Ele carrega:

- `tenantId`;
- `scheduleId`;
- `dueAt`;
- payload já serializado como JSON.

## Encaixe arquitetural

O controller transforma o contrato HTTP nesse comando. A partir desse ponto, o caso de uso não precisa conhecer annotations, objetos de request ou detalhes do servlet.

O comando está na camada de aplicação porque descreve a intenção de executar um caso de uso, não o estado persistente do domínio.

## Decisão de design

É um `record` imutável e pequeno. Validação de transporte acontece no controller; invariantes definitivas permanecem em `Schedule`, evitando que outro adapter consiga contorná-las.

## Evolução esperada

Targets reais, política de retry, idempotency key e misfire policy ampliarão esse comando somente quando entrarem no escopo de um experimento.
