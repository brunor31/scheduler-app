# `CreateScheduleService`

## Papel

`CreateScheduleService` orquestra a criação de um schedule sem conter detalhes de HTTP, SQL ou threads.

O fluxo é:

```text
obter now do Clock
  ↓
construir Schedule e validar invariantes
  ↓
persistir
  ↓
publicar no timer local
```

## Por que persistir antes do timer

O ACK de criação só pode se apoiar em estado durável. Se o processo cair depois do commit e antes da publicação local, a reconciliação pode reconstruir a entrada. Fazer a ordem inversa permitiria um timer apontar para um registro inexistente.

## Encaixe arquitetural

A classe implementa a porta de entrada `CreateScheduleUseCase` e depende apenas das portas `ScheduleRepository`, `ScheduleTimer` e do `Clock` padrão Java.

Ela não usa annotations do Spring. O wiring é feito externamente por `SchedulerConfiguration`.
