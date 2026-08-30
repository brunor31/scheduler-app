# `SchedulerConfiguration`

## Papel

`SchedulerConfiguration` monta o grafo de objetos da aplicação.

Ela fornece:

- `Clock.systemUTC()`;
- `CreateScheduleService` como `CreateScheduleUseCase`;
- `HandleDueScheduleService` como `HandleDueScheduleUseCase`;
- habilitação do scheduler periódico do Spring.

## Encaixe arquitetural

Os serviços de aplicação não possuem annotations do Spring. A configuração, localizada na infraestrutura, injeta as implementações das portas.

Essa composição preserva a direção:

```text
infrastructure → application → domain
```

## Clock

Centralizar o `Clock` evita chamadas dispersas a `Instant.now()` na lógica. Testes unitários podem construir os serviços com `Clock.fixed`, enquanto a aplicação real recebe UTC.

## Evolução

Novos adapters podem ser selecionados aqui ou por configurações específicas. A classe não deve acumular regras de negócio.
