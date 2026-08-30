# `CreateScheduleUseCase`

## Papel

`CreateScheduleUseCase` é a porta de entrada da aplicação para criar schedules.

```java
Schedule create(CreateScheduleCommand command);
```

Ela define o que a aplicação oferece, sem definir como HTTP chama a operação ou como o schedule é persistido.

## Encaixe arquitetural

O adapter web depende dessa interface. A implementação concreta, `CreateScheduleService`, fica na camada de aplicação.

Essa inversão impede que o controller dependa diretamente de PostgreSQL ou do timer local. Também permite testar o adapter e substituir a forma de exposição do caso de uso.

## Contrato atual

Uma criação bem-sucedida devolve o `Schedule` inicial. Violações de domínio e duplicidade são propagadas como exceções específicas para tradução na borda.
