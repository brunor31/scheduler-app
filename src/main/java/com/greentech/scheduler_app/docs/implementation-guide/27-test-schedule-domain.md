# `ScheduleTest`

## Papel

`ScheduleTest` cobre as invariantes iniciais do objeto de domínio sem framework.

## Cenários

O teste verifica que uma criação válida produz:

- versão `1`;
- estado `SCHEDULED`;
- target `FAKE`;
- timestamps baseados no instante fornecido;
- ausência de `deliveredAt`.

Também confirma que `dueAt` igual ao instante atual é rejeitado com `InvalidScheduleException`.

## Encaixe na estratégia

É um teste unitário de domínio: rápido, determinístico e independente de Spring, banco e relógio da máquina.

Novas transições de estado deverão receber testes nesse nível antes de serem exercitadas pela API ou persistência.
