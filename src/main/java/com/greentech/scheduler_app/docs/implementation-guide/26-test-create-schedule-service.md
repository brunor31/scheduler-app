# `CreateScheduleServiceTest`

## Papel

Este teste unitário verifica a orquestração do caso de uso de criação sem iniciar Spring ou PostgreSQL.

## Cenário

São usadas implementações de gravação simples para `ScheduleRepository` e `ScheduleTimer`, além de um `Clock.fixed`.

O teste prova que:

- o schedule é construído com o instante controlado;
- o mesmo objeto é persistido;
- a publicação no timer acontece depois da escrita no repositório.

## Por que não usar contexto Spring

A regra verificada pertence à aplicação e não depende de infraestrutura. Um teste puro é mais rápido, mais determinístico e deixa explícitas as colaborações do serviço.

## Limite

Commit real e recuperação após falha são responsabilidades dos testes PostgreSQL e dos experimentos de restart.
