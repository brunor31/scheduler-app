# `ScheduleRecoveryLoader`

## Papel

`ScheduleRecoveryLoader` reconstrói e reabastece a fila local a partir da fonte de verdade persistente.

Ele executa:

- uma carga quando a aplicação fica pronta;
- reconciliações periódicas lentas;
- busca até um look-ahead configurável;
- publicação de cada resultado em `ScheduleTimer`.

## Encaixe arquitetural

A classe é um adapter de infraestrutura temporal. Ela usa somente as portas `ScheduleRepository` e `ScheduleTimer`, sem duplicar regras de execução.

## Por que existe reconciliação

A publicação local após create pode ser perdida se o processo cair entre commit e enqueue. A reconciliação restaura o item e também avança a janela para schedules distantes.

Ela é um safety net, não um relógio de alta frequência. O deadline preciso fica na fila local.

## Configuração atual

O padrão é uma janela de 15 minutos, intervalo de 60 segundos e lote de até 10.000 itens. Esses números são hipóteses operacionais, não capacidade validada.
