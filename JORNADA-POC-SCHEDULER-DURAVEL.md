# Construindo uma plataforma de agendamento durável: objetivos, experimentos e decisões

## Por que este documento existe

Este documento registra a evolução de uma prova de conceito de uma plataforma de agendamento durável. Ele foi escrito para cumprir dois papéis:

1. preservar o raciocínio técnico que levou às decisões do projeto;
2. servir como fonte para artigos, apresentações e publicações no LinkedIn sem transformar resultados locais em promessas de produção.

O ponto central da jornada é simples:

> Arquitetura não deve evoluir apenas porque uma solução mais sofisticada parece interessante. Primeiro implementamos o caminho mais simples que pode estar correto, depois medimos seus limites e só então adicionamos complexidade.

---

## O problema que queremos explorar

Um scheduler recebe uma intenção de execução futura:

```text
Execute a ação X para o tenant Y no instante dueAt.
```

Apesar de parecer um timer comum, uma plataforma durável precisa responder perguntas difíceis:

- O agendamento sobrevive à reinicialização do processo?
- O que acontece quando milhares de ações vencem no mesmo instante?
- Como cancelamento e reagendamento evitam uma execução obsoleta?
- Como múltiplas instâncias impedem disputas ou duplicidades descontroladas?
- O que acontece quando o destino responde, mas o processo cai antes de confirmar a entrega?
- Qual precisão e throughput a arquitetura realmente oferece?

A POC não tenta responder tudo de uma só vez. Cada etapa isola uma incerteza.

---

## Objetivos iniciais

### Objetivo técnico

Descobrir, por implementação e benchmark, qual é a arquitetura mais simples capaz de sustentar um scheduler durável com:

- persistência autoritativa;
- execução baseada em um instante absoluto;
- recuperação após perda do estado em memória;
- isolamento entre tenants;
- evolução segura para cancelamento, reagendamento e concorrência;
- semântica honesta de entrega `at-least-once`;
- métricas que permitam avaliar precisão e capacidade.

### Objetivo de arquitetura

Preservar Clean Architecture:

```text
adapter
   ↓
application
   ↓
domain
```

O domínio não conhece Spring, HTTP, PostgreSQL, filas ou métricas. A aplicação coordena casos de uso e define portas. Os adapters traduzem HTTP, persistência, timer e entrega.

### Objetivo de descoberta

Evitar decisões antecipadas como Kafka, Redis, timer wheel, sharding, leases ou múltiplos serviços antes de existir evidência de que a solução atual é insuficiente.

---

## Princípios adotados

### PostgreSQL como fonte de verdade

O estado persistido é autoritativo. A estrutura em memória existe para esperar o instante de execução com precisão, mas pode ser descartada e reconstruída.

```text
PostgreSQL
    ↓ carrega timers próximos
DelayQueue local
    ↓ dueAt
execução
```

### Tempo em UTC

Os instantes são representados por `Instant`. Um horário como `20:53:00-03:00` é o mesmo instante que `23:53:00Z`.

### Sem promessa de exactly-once

Consultar o estado antes da entrega e atualizar para `DELIVERED` depois dela não elimina a janela de falha entre o efeito externo e a confirmação no banco. A plataforma assume `at-least-once`, e idempotência faz parte do contrato lógico de entrega.

### Evolução orientada por evidência

Cada experimento segue:

```text
hipótese
  → implementação mínima
  → workload controlado
  → medição
  → conclusão
  → decisão
```

---

## Arquitetura implementada no POC-0

```text
POST /v1/tenants/{tenantId}/schedules
                ↓
       ScheduleController
                ↓
      CreateScheduleService
          ├── PostgreSQL
          └── DelayQueue
                  ↓ dueAt
       local-timer-engine
                  ↓
      consulta estado no PostgreSQL
                  ↓
         FakeScheduleTarget
                  ↓
   atualização condicional para DELIVERED
```

### Regras atuais

- `tenantId + scheduleId` identifica unicamente um schedule;
- `dueAt` precisa estar no futuro na criação;
- a primeira versão é `1`;
- o estado inicial é `SCHEDULED`;
- a entrega bem-sucedida muda o estado para `DELIVERED`;
- uma criação duplicada retorna HTTP `409`;
- o payload JSON é persistido como JSONB;
- o target do POC-0 é propositalmente `FAKE`.

### Por que `DelayQueue`

`DelayQueue` mantém os timers ordenados por deadline e bloqueia o worker até o item mais próximo vencer. Ela não executa tarefas; apenas informa quando um item está elegível.

O deadline local usa `System.nanoTime()` para espera monotônica, enquanto `dueAt` permanece um `Instant` persistido. O PostgreSQL continua sendo consultado antes da entrega para confirmar estado e versão.

---

## Roadmap das POCs

### POC-0 — Timer durável local — concluído

Objetivo:

- criar e persistir um schedule;
- carregá-lo no timer local;
- esperar até `dueAt`;
- executar um target sem latência de rede;
- marcar a entrega;
- medir a precisão básica e bursts concentrados.

Resultado:

- fluxo funcional comprovado contra PostgreSQL real;
- documentação OpenAPI e testes de integração;
- nenhuma perda nos workloads executados;
- limite do consumidor serial medido.

### POC-0b — Entrega local paralela e limitada — concluído

Esta etapa não fazia parte do caminho funcional mínimo. Ela foi criada como consequência direta do benchmark do POC-0.

Objetivo:

- comparar 1, 2, 4 e 8 workers;
- reduzir a cauda de latência em bursts;
- usar fila e concorrência limitadas;
- implementar backpressure;
- impedir execução local simultânea da mesma versão;
- descobrir quando PostgreSQL ou pool de conexões passam a limitar a vazão.

Hipótese inicial:

> Quatro workers podem reduzir o p99 do burst de 10 mil de aproximadamente 9,2 segundos para menos de 3 segundos, sem perdas, duplicidades ou saturação do PostgreSQL.

Essa é uma hipótese experimental, não um SLO de produto.

Resultado:

- a hipótese foi confirmada no ambiente local;
- com 10 mil schedules, quatro workers reduziram o p99 mediano de 8.969,746 ms para 2.579,893 ms;
- a redução de p99 foi de 71,2%, com throughput mediano de 3.849,49 entregas/s;
- a fila limitada atingiu sua capacidade de 1.000 itens, comprovando backpressure no burst;
- não houve conexões Hikari pendentes nas amostras;
- quatro workers foram escolhidos por serem a menor configuração que cumpriu a meta experimental.

Oito workers chegaram a p99 mediano de 2.003,207 ms e 4.964,40 entregas/s, mas apresentaram variação maior entre rodadas. A evidência não justifica esse paralelismo adicional para a próxima POC.

### POC-1 — Restart e recovery — planejado

Objetivo:

- criar schedules futuros;
- desligar a aplicação;
- reiniciá-la antes ou depois de `dueAt`;
- reconstruir a fila local pelo PostgreSQL;
- medir tempo de recuperação e atraso de itens overdue.

O evento de sleep observado no benchmark produziu um exemplo real da importância dessa etapa.

### POC-2 — Cancelamento e reagendamento — planejado

Objetivo:

- implementar cancelamento;
- implementar reagendamento com incremento de versão;
- garantir que uma entrada antiga da fila nunca execute uma versão obsoleta;
- validar lazy invalidation.

### POC-3 — Concorrência — planejado

Objetivo:

- testar cancelamento contra execução;
- testar update contra execução;
- testar duas mutações com a mesma versão esperada;
- comprovar atualizações condicionais e limites transacionais em PostgreSQL real.

### POC-4 — Múltiplas instâncias — planejado

Objetivo:

- executar duas instâncias sobre o mesmo PostgreSQL;
- medir disputas sobre o mesmo schedule;
- descobrir se claims, `SKIP LOCKED`, ownership, leases ou fencing são realmente necessários.

### POC-5 — Retry e falhas ambíguas — planejado

Objetivo:

- modelar sucesso, erro temporário, erro permanente e resultado desconhecido;
- testar timeout e target lento;
- testar queda depois do sucesso externo e antes da confirmação persistida;
- validar o contrato de idempotência.

### POC-6 — Operating envelope completo — planejado

Objetivo:

- medir criação, atualização, cancelamento e entrega;
- testar bursts e carga sustentada;
- testar degradação do target e crescimento de backlog;
- registrar o limite comprovado de cada versão da arquitetura.

O burst do POC-0 antecipa apenas uma dimensão dessa etapa: entrega concentrada com target fake e uma única instância.

---

## Como o benchmark foi executado

### Workload

Foram criados schedules com exatamente o mesmo `dueAt`:

- 100 schedules;
- 1.000 schedules;
- 5.000 schedules;
- 10.000 schedules.

Cada cenário teve três repetições válidas. Todos os schedules eram carregados antes do vencimento e cada execução recebia um prefixo exclusivo.

### Métrica principal

```text
completion lag = delivered_at - due_at
```

Essa métrica inclui:

- espera do timer;
- leitura do PostgreSQL;
- execução do target fake;
- atualização condicional para `DELIVERED`.

Ela é ligeiramente mais conservadora que medir apenas o início do dispatch.

### Ambiente

- macOS em Apple Silicon;
- Java 21 na aplicação;
- Docker/Colima em `arm64`;
- aproximadamente 1,9 GiB destinados ao ambiente Colima;
- PostgreSQL 17 Alpine;
- uma instância da aplicação;
- um worker de timer;
- target fake síncrono.

São resultados locais e não representam capacidade de produção.

---

## Resultados do POC-0

Os valores são medianas das três repetições válidas.

| Schedules no mesmo instante | Entregas/s | p50 | p99 | Atraso máximo |
|---:|---:|---:|---:|---:|
| 100 | 348,21 | 204,056 ms | 301,517 ms | 302,870 ms |
| 1.000 | 942,78 | 597,526 ms | 1.059,679 ms | 1.069,838 ms |
| 5.000 | 1.082,82 | 2.345,135 ms | 4.582,052 ms | 4.623,526 ms |
| 10.000 | 1.077,80 | 4.657,904 ms | 9.197,872 ms | 9.288,154 ms |

### Volume total executado

- matriz oficial válida: 48.300 schedules;
- warm-up: 100 schedules;
- rodada interrompida por sleep: 5.000 schedules;
- total criado e entregue durante a campanha: 53.400 schedules.

Não houve perda de schedule nessas execuções.

### O que os números mostram

A partir de 1.000 schedules, a vazão ficou próxima de 1.000 conclusões por segundo. O p50 ficou próximo do tempo necessário para processar metade da fila, e a cauda ficou próxima do tempo para drenar quase toda a fila.

Isso indica que `DelayQueue` não foi o gargalo dominante. Depois que os timers ficaram elegíveis, um único worker executou serialmente:

```text
read PostgreSQL
  → fake dispatch
  → update PostgreSQL
  → próximo schedule
```

Com vazão próxima de 1.000/s:

```text
1.000 itens  → cauda próxima de 1 segundo
5.000 itens  → cauda próxima de 5 segundos
10.000 itens → cauda próxima de 10 segundos
```

O comportamento medido corresponde a essa expectativa.

---

## O outlier que virou aprendizado

Uma das execuções de 5 mil schedules apresentou:

- primeira conclusão aproximadamente 31,6 segundos atrasada;
- p99 de aproximadamente 36,6 segundos;
- drenagem normal, próxima de cinco segundos, depois que o processamento retomou.

O log de energia do macOS mostrou que o notebook entrou em idle sleep às `21:33:23` e acordou às `21:33:57`, atravessando o `dueAt` das `21:33:28`.

Esse resultado não foi apagado nem misturado ao benchmark contínuo. Ele foi classificado corretamente:

- não mede throughput, porque o processo estava suspenso;
- demonstra comportamento overdue depois de uma interrupção ambiental;
- reforça a necessidade do POC-1;
- levou o harness a usar `caffeinate` no macOS.

Um outlier não é necessariamente “ruído”. Às vezes ele revela a próxima pergunta arquitetural.

---

## Decisões tomadas até aqui

### 1. Manter PostgreSQL como fonte de verdade

A fila local é reconstruível. Nenhuma garantia depende exclusivamente da memória do processo.

### 2. Manter semântica `at-least-once`

Não existe promessa artificial de exactly-once. A janela entre efeito externo e confirmação persistida será tratada nos experimentos de falha e idempotência.

### 3. Não introduzir mensageria ainda

Os resultados não justificam Kafka, RabbitMQ ou Redis. O gargalo observado é o caminho serial local de entrega.

### 4. Não trocar `DelayQueue` por timer wheel

O benchmark não mostrou que a estrutura de espera é o gargalo. Trocar o timer agora atacaria o componente errado.

### 5. Criar o POC-0b de concorrência local limitada

O plateau de aproximadamente 1.000 conclusões/s e o crescimento linear da cauda fornecem evidência para comparar múltiplos workers.

### 6. Exigir backpressure

Paralelismo não será implementado com threads ou filas ilimitadas. A nova etapa deve proteger memória, pool de conexões e PostgreSQL.

### 7. Preservar proteção contra duplicidade local

Com múltiplos workers, uma reconciliação não pode recolocar a mesma versão enquanto ela estiver em execução. Essa invariável precisa ser testada antes do benchmark paralelo.

### 8. Adiar coordenação distribuída

Claims, leases, fencing e múltiplas instâncias pertencem a POCs posteriores. Paralelismo dentro de um processo não autoriza antecipar coordenação distribuída.

### 9. Selecionar quatro workers para a próxima POC

Quatro workers foram a menor configuração que manteve o p99 mediano abaixo de três segundos no burst de 10 mil. Oito workers foram mais rápidos, mas adicionaram paralelismo desnecessário para a meta atual e exibiram maior variação entre repetições.

---

## O que ainda não foi provado

- comportamento com target HTTP ou broker real;
- carga sustentada por horas;
- restart recovery controlado;
- cancelamento e reagendamento;
- concorrência entre mutações;
- múltiplas instâncias;
- retries e resultados ambíguos;
- capacidade de produção;
- um SLO de negócio.

Também não devemos concluir que “1.000 schedules/s” é um limite universal. Esse valor pertence ao hardware, configuração, banco e código medidos.

---

## Próxima decisão de produto

Antes de classificar o resultado como suficiente ou insuficiente, precisamos definir:

```text
Qual burst simultâneo importa para o negócio?
Qual p99 de atraso é aceitável para esse burst?
```

Exemplos de perguntas, não requisitos já aprovados:

- p99 abaixo de um segundo para mil schedules?
- p99 abaixo de três segundos para dez mil?
- ou dez segundos para dez mil já atende ao caso de uso?

Sem esse objetivo, o benchmark caracteriza a arquitetura, mas não determina aprovação ou reprovação.

---

## Resultado do POC-0b

```text
DelayQueue
    ↓
coordenador de deadlines
    ↓
executor limitado
    ├── worker 1
    ├── worker 2
    ├── worker 3
    └── worker N
```

Comparação medida para 10 mil schedules no mesmo instante:

| Configuração | Entregas/s medianas | p99 mediano | Decisão |
|---|---:|---:|---|
| 1 worker | 1.105,71 | 8.969,746 ms | baseline reproduzida |
| 2 workers | 2.656,57 | 3.733,888 ms | meta ainda não atingida |
| 4 workers | 3.849,49 | 2.579,893 ms | menor configuração que atingiu a meta |
| 8 workers | 4.964,40 | 2.003,207 ms | ganho adicional com maior variação |

Critérios:

- 100% entregues;
- nenhuma duplicidade local;
- fila limitada;
- backpressure observável;
- p50, p95, p99 e p99.9;
- throughput;
- utilização do pool de conexões;
- CPU e memória da aplicação e PostgreSQL.

A matriz consolidada contém 46 relatórios válidos e 193 mil schedules aceitos e entregues. Todos os cenários de 1 mil, 5 mil e 10 mil possuem três repetições válidas. Os cenários de 100 itens com quatro e oito workers possuem duas: a terceira tentativa de cada um foi corretamente invalidada por terminar a carga com apenas nove segundos antes do `dueAt`. Essa lacuna não afeta a decisão baseada em 10 mil, mas permanece documentada.

---

## Ideias centrais para uma publicação no LinkedIn

### Possíveis títulos

- “O que 53 mil timers me ensinaram sobre construir um scheduler durável”
- “DelayQueue não era o gargalo: medindo antes de redesenhar”
- “De um timer local a uma plataforma durável: arquitetura guiada por POCs”
- “Por que não comecei com Kafka para construir um scheduler”

### Estrutura narrativa sugerida

1. Comece com a falsa simplicidade de “execute algo no futuro”.
2. Apresente PostgreSQL como verdade e `DelayQueue` como índice temporário.
3. Explique por que exactly-once não foi prometido.
4. Mostre o primeiro fluxo funcionando.
5. Apresente o burst de 100, 1k, 5k e 10k.
6. Mostre o plateau próximo de 1.000 entregas/s.
7. Conte o outlier de sleep e como ele virou aprendizado.
8. Mostre como quatro workers reduziram o p99 de 10 mil itens em 71,2%.
9. Explique por que oito workers não foram escolhidos mesmo sendo mais rápidos.
10. Termine com a ideia de arquitetura evoluindo por evidência.

### Números que podem ser usados

- 53.400 schedules criados e entregues em toda a campanha local;
- 48.300 schedules na matriz oficial válida;
- três repetições por cenário;
- aproximadamente 1.000 conclusões/s a partir de bursts de 1k;
- p99 de aproximadamente 1,06 s para 1k;
- p99 de aproximadamente 4,58 s para 5k;
- p99 de aproximadamente 9,20 s para 10k;
- zero perdas observadas no experimento;
- um outlier de 36,6 s explicado por 34 segundos de sleep do host.

Sempre acompanhe esses números de “ambiente local, PostgreSQL em Colima e target fake”.

Números da segunda campanha:

- 193 mil schedules nos 46 relatórios válidos do POC-0b;
- p99 de 8,97 s com um worker e 2,58 s com quatro workers em 10 mil itens;
- redução de 71,2% no p99;
- aproximadamente 3,48 vezes o throughput da nova baseline;
- fila de entrega limitada em 1.000 itens e zero conexões pendentes observadas.

---

## Rascunho editável para LinkedIn

> “Execute esta ação daqui a alguns minutos” parece um problema simples — até você precisar sobreviver a restarts, reagendamentos, bursts, falhas ambíguas e múltiplas instâncias.
>
> Estou construindo uma POC de uma plataforma de agendamento durável com uma regra: não adicionar complexidade antes de medir a solução mais simples.
>
> A primeira arquitetura usa PostgreSQL como fonte de verdade e uma `DelayQueue` local apenas para esperar deadlines próximos. O fluxo persiste o schedule, espera `dueAt`, consulta novamente o estado, executa um target fake e confirma `DELIVERED`. A semântica é `at-least-once`; não existe promessa mágica de exactly-once.
>
> Depois de validar o fluxo, concentrei 100, 1 mil, 5 mil e 10 mil schedules no mesmo instante, com três repetições por cenário. Na matriz válida, 48.300 schedules foram entregues sem perdas.
>
> O resultado mais interessante: acima de mil itens, o consumidor único estabilizou próximo de 1.000 conclusões por segundo. O p99 cresceu de ~1,06 s em 1 mil para ~9,20 s em 10 mil.
>
> A `DelayQueue` não era o gargalo. O limite estava depois do deadline: um worker executando serialmente leitura no PostgreSQL, dispatch e atualização de estado.
>
> Também apareceu um outlier de 36,6 s. Em vez de descartá-lo, fui ao log de energia: o notebook havia dormido por 34 segundos atravessando o deadline. O dado não media throughput, mas revelou um ótimo cenário para a próxima POC de recovery.
>
> O experimento seguinte comparou 1, 2, 4 e 8 workers sem trocar a `DelayQueue`, o PostgreSQL ou o tamanho do pool. Quatro workers reduziram o p99 de 10 mil itens em 71,2%, para aproximadamente 2,58 s, e cumpriram a hipótese. Oito chegaram a aproximadamente 2,00 s, mas com variação maior. Por isso escolhi a menor configuração suficiente, não a mais concorrente.
>
> Minha principal conclusão até agora: bons experimentos não servem apenas para confirmar uma arquitetura. Eles mostram qual complexidade ainda não precisamos adicionar — e qual é a próxima incerteza que merece ser isolada. Agora essa incerteza é recovery após restart.

---

## Referências internas

- [Arquitetura](src/main/java/com/greentech/scheduler_app/docs/architecture.md)
- [Estratégia das POCs](src/main/java/com/greentech/scheduler_app/docs/poc-strategy.md)
- [Estratégia de testes](src/main/java/com/greentech/scheduler_app/docs/testing-strategy.md)
- [Experimento POC-0](src/main/java/com/greentech/scheduler_app/docs/experiments/poc-0-local-durable-timer.md)
- [Protocolo de burst](src/main/java/com/greentech/scheduler_app/docs/experiments/poc-0-burst-benchmark.md)
- [Resultados detalhados](src/main/java/com/greentech/scheduler_app/docs/experiments/poc-0-burst-results-2026-08-29.md)
- [Resultados do POC-0b](src/main/java/com/greentech/scheduler_app/docs/experiments/poc-0b-bounded-parallel-results-2026-08-29.md)
- [Operating envelope](src/main/java/com/greentech/scheduler_app/docs/operating-envelope.md)

---

## Estado atual

```text
POC-0  timer durável local          concluído
POC-0b entrega paralela limitada    concluído; 4 workers selecionados
POC-1  restart/recovery             planejado
POC-2  cancel/reschedule            planejado
POC-3  concorrência                 planejado
POC-4  múltiplas instâncias         planejado
POC-5  retry/falhas                 planejado
POC-6  operating envelope completo  planejado
```

O projeto continua deliberadamente incompleto. Esse é o propósito da POC: resolver uma incerteza por vez e preservar evidência suficiente para justificar cada próxima decisão.
