# `application.properties`

## Papel

O arquivo configura o runtime inicial sem introduzir decisões de negócio nas classes.

## Persistência

```properties
spring.jpa.hibernate.ddl-auto=none
spring.jpa.open-in-view=false
```

O schema é responsabilidade do Flyway. Open Session in View fica desabilitado para evitar acesso implícito ao banco na camada web.

## Observabilidade

Health e metrics são expostos pelo Actuator para inspeção do POC.

## OpenAPI e Swagger UI

```properties
springdoc.api-docs.path=/v3/api-docs
springdoc.swagger-ui.path=/swagger-ui.html
springdoc.swagger-ui.operations-sorter=method
springdoc.swagger-ui.tags-sorter=alpha
```

O contrato OpenAPI fica disponível em `/v3/api-docs` e a interface interativa em `/swagger-ui.html`. As opções de ordenação tornam a apresentação estável à medida que novos endpoints forem adicionados.

## Timer

```properties
scheduler.timer.look-ahead=15m
scheduler.timer.reconciliation-interval=60s
scheduler.timer.recovery-batch-size=10000
scheduler.timer.delivery-workers=1
scheduler.timer.delivery-queue-capacity=1000
```

Esses valores controlam carga antecipada, safety net, tamanho máximo por consulta, paralelismo de entrega e capacidade da fila pronta. Um worker mantém a baseline serial; a fila limitada aplica backpressure. São defaults de experimento e precisam ser medidos antes de qualquer afirmação de capacidade.

## Encaixe arquitetural

A configuração altera parâmetros técnicos dos adapters. Ela não muda invariantes do domínio nem cria garantias distribuídas.
