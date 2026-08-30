# Migração `V1__create_schedule.sql`

## Papel

A primeira migração Flyway cria a representação persistente mínima do POC-0.

## Estrutura

A tabela `schedule` possui chave primária composta por tenant e schedule, versão positiva, `due_at` em `timestamptz`, estado, target, payload `jsonb` e timestamps de criação, atualização e entrega.

Constraints limitam os estados a `SCHEDULED` e `DELIVERED` e o target a `FAKE`, refletindo apenas a funcionalidade realmente implementada.

## Índice

O índice parcial `idx_schedule_due` contém somente registros `SCHEDULED` e ordena por prazo e identidade. Ele atende a query de look-ahead sem indexar schedules já entregues.

## Encaixe arquitetural

Flyway é a única fonte de evolução do schema. `ddl-auto` não cria nem atualiza tabelas.

## Evolução

Novos estados ou colunas exigem uma nova migration. Não se deve reescrever a V1 depois que ela tiver sido aplicada em ambientes compartilhados.
