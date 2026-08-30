# `ScheduleRepository`

## Papel

`ScheduleRepository` é a porta de saída para o estado persistente dos schedules.

As operações atuais permitem:

- inserir um schedule;
- buscar pela chave composta tenant/schedule;
- carregar schedules dentro do look-ahead;
- marcar uma versão `SCHEDULED` como `DELIVERED` condicionalmente.

## Encaixe arquitetural

A interface fica na aplicação. O PostgreSQL é implementado por `JdbcScheduleRepository`, na camada de adapter de saída.

Com isso, domínio e casos de uso não importam JDBC, SQL ou classes do driver PostgreSQL.

## Detalhe importante

`markDelivered` recebe a versão esperada e retorna `boolean`. O `UPDATE` condicional impede que uma alteração concorrente invisível seja tratada como sucesso.

Isso ainda não é um protocolo distribuído completo; claims, leases e fencing pertencem aos POCs posteriores.
