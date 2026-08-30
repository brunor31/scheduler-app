# `LocalTimerSchedulerTest`

## Papel

O teste valida as propriedades locais introduzidas pelo POC-0b sem iniciar Spring ou PostgreSQL. O handler controlado usa latches para tornar as disputas reproduzíveis.

## Concorrência máxima e backpressure

Com dois workers, fila de capacidade dois e dez timers vencidos, o teste bloqueia a primeira onda e observa:

- exatamente duas entregas em andamento;
- exatamente dois itens na fila pronta;
- itens restantes ainda na `DelayQueue`;
- concorrência máxima igual a dois;
- todas as dez chamadas concluídas depois da liberação.

Isso demonstra que o coordenador não cria trabalho pronto ilimitado e não atua como worker adicional.

## Deduplicação em execução

O teste bloqueia uma versão dentro do handler e tenta agendá-la novamente. A métrica de chaves ativas permanece em um e o handler recebe somente uma chamada.

Essa cobertura protege a mudança mais sensível do POC-0b: a chave só é liberada depois do processamento, impedindo que a reconciliação crie duas execuções locais concorrentes da mesma versão.

## Configuração inválida

Workers e capacidade precisam ser positivos. A falha ocorre no construtor para impedir que a aplicação inicie com uma configuração incapaz de processar ou armazenar trabalho pronto.

## Limite

O teste comprova concorrência dentro de uma instância. Coordenação entre processos e semântica PostgreSQL continuam pertencendo às POCs posteriores.
