# `FakeScheduleTarget`

## Papel

`FakeScheduleTarget` implementa `ScheduleTarget` sem realizar chamada de rede. A entrega atual apenas registra em log tenant, schedule e versão.

## Por que um target fake

O POC-0 quer medir e provar o timer com o mínimo de ruído externo. HTTP, broker, autenticação e latência de rede introduziriam variáveis que pertencem a outros experimentos.

## Encaixe arquitetural

A classe é um adapter de saída. `HandleDueScheduleService` não sabe se o destino é fake, HTTP ou SQS; ele chama a porta `ScheduleTarget`.

## Semântica

Este adapter sempre retorna com sucesso. Portanto, ele não prova retries, idempotência end-to-end ou tratamento de unknown outcome.

Targets reais deverão propagar uma idempotency key e classificar resultados quando essa semântica for implementada.
