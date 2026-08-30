# `LocalTimerScheduler`

## Papel

`LocalTimerScheduler` implementa `ScheduleTimer` com uma `DelayQueue`, estrutura baseada em heap que entrega primeiro o menor deadline.

Cada entrada local contém apenas:

- tenant;
- schedule ID;
- versão;
- deadline monotônico.

Payload e estado completo permanecem no PostgreSQL.

## Relógio

Ao inserir, a classe calcula a duração entre `Clock.instant()` e `dueAt`. Essa duração é convertida em um deadline baseado em `System.nanoTime()`, evitando que ajustes posteriores no relógio de parede alterem a espera já programada.

## Execução

Uma thread coordenadora bloqueia em `timerQueue.take()`. Quando o item vence, ela o transfere para uma `ArrayBlockingQueue` limitada. Um conjunto configurável de workers retira entradas dessa fila e chama `HandleDueScheduleUseCase`, que volta ao banco e valida estado e versão antes do target.

```text
DelayQueue
    ↓ coordenador
ArrayBlockingQueue limitada
    ↓
N delivery workers
```

Quando a fila de entrega enche, `put` bloqueia o coordenador. Esse backpressure mantém o trabalho pronto limitado e deixa os demais itens na `DelayQueue`, sem criar threads ou filas ilimitadas.

Um conjunto concorrente deduplica republicações feitas pela reconciliação. A chave permanece ativa até o `finally` da entrega, e não apenas até sair da `DelayQueue`; assim, a mesma versão não pode ser recarregada enquanto aguarda worker ou está em execução.

## Configuração

```properties
scheduler.timer.delivery-workers=1
scheduler.timer.delivery-queue-capacity=1000
```

Um worker preserva a baseline do POC-0. O POC-0b compara 1, 2, 4 e 8 sem alterar a estrutura do timer.

## Métricas

- `scheduler.timer.queue.size`: deadlines ainda no timer;
- `scheduler.delivery.queue.size`: itens vencidos aguardando worker;
- `scheduler.delivery.in.progress`: entregas em execução;
- `scheduler.timer.active.keys`: versões entre publicação e fim do processamento.

## Encaixe arquitetural

Threads, `DelayQueue`, Micrometer e lifecycle Spring pertencem ao adapter. A aplicação enxerga apenas `ScheduleTimer`.

## Limites

Continua sendo um motor de instância única. A proteção é local e não fornece ownership distribuído, claim, lease ou fencing. O heap não deve ser trocado por timer wheel sem benchmark.
