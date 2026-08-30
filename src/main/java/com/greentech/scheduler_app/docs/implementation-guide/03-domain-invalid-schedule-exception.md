# `InvalidScheduleException`

## Papel

`InvalidScheduleException` comunica que uma tentativa de construir um `Schedule` violou uma regra do domínio.

Exemplos atuais:

- identificadores vazios;
- versão inválida;
- `dueAt` ausente ou não futuro.

## Encaixe arquitetural

A exceção pertence ao domínio porque descreve uma violação de invariante, sem assumir como o erro será transportado.

O adapter web traduz essa exceção para HTTP 400. Outro adapter, como gRPC ou linha de comando, poderia traduzi-la de outra maneira sem alterar o domínio.

## Decisão de design

Foi usada uma exceção específica em vez de `IllegalArgumentException` para que a borda HTTP consiga distinguir uma entrada de negócio inválida de uma falha inesperada da aplicação.
