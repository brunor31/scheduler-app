# Desenvolvimento local e primeira carga manual

## Objetivo

Este guia prepara uma instância local do POC-0, cria os primeiros schedules pela API e confirma no PostgreSQL a transição de `SCHEDULED` para `DELIVERED`.

O target atual é `FAKE`: a entrega aparece no log da aplicação e no estado persistido, sem realizar uma chamada externa.

## Requisitos

- JDK 21, com `java -version` apontando para essa instalação;
- Docker Desktop ou Colima com o daemon Docker ativo;
- `curl`;
- porta `5432` livre para o PostgreSQL;
- porta `8080` livre para a aplicação.

O Maven Wrapper está versionado no projeto, portanto não é necessário instalar Maven globalmente.

Confira o ambiente:

```bash
java -version
docker version
curl --version
```

## 1. Iniciar o PostgreSQL

Crie um PostgreSQL 17 dedicado ao projeto:

```bash
docker run --name scheduler-postgres \
  -e POSTGRES_DB=scheduler \
  -e POSTGRES_USER=scheduler \
  -e POSTGRES_PASSWORD=scheduler \
  -p 5432:5432 \
  -d postgres:17-alpine
```

Nas próximas execuções, se o container já existir e estiver parado:

```bash
docker start scheduler-postgres
```

Confirme que o banco está pronto:

```bash
docker exec scheduler-postgres pg_isready -U scheduler -d scheduler
```

## 2. Configurar a conexão

No terminal em que a aplicação será iniciada, exporte:

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/scheduler'
export SPRING_DATASOURCE_USERNAME='scheduler'
export SPRING_DATASOURCE_PASSWORD='scheduler'
```

Essas variáveis sobrescrevem as propriedades equivalentes do Spring Boot sem armazenar credenciais no repositório. Na inicialização, o Flyway cria a tabela e o índice definidos em `V1__create_schedule.sql`; Hibernate não cria nem atualiza o schema.

## 3. Executar a aplicação

Na raiz do projeto:

```bash
./mvnw spring-boot:run
```

A aplicação está pronta quando o log informar que o servidor iniciou na porta `8080`. Em outro terminal, verifique:

```bash
curl -i http://localhost:8080/actuator/health
```

O resultado esperado é HTTP `200` e status `UP`.

## 4. Abrir a documentação da API

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- OpenAPI YAML: <http://localhost:8080/v3/api-docs.yaml>

O botão **Try it out** do Swagger UI permite executar a mesma criação descrita abaixo.

## 5. Criar o primeiro schedule

No macOS, calcule um prazo 30 segundos no futuro:

```bash
export DUE_AT="$(date -u -v+30S '+%Y-%m-%dT%H:%M:%SZ')"
```

Crie o schedule:

```bash
curl -i -X POST 'http://localhost:8080/v1/tenants/tenant-manual/schedules' \
  -H 'Content-Type: application/json' \
  -d "{\"scheduleId\":\"schedule-001\",\"dueAt\":\"${DUE_AT}\",\"payload\":{\"kind\":\"manual-test\"}}"
```

Resultado esperado:

- HTTP `201 Created`;
- header `Location: /v1/tenants/tenant-manual/schedules/schedule-001`;
- header `ETag: "1"`;
- body com `state: "SCHEDULED"` e `targetType: "FAKE"`.

## 6. Observar persistência e entrega

Antes do vencimento, consulte o registro:

```bash
docker exec scheduler-postgres psql -U scheduler -d scheduler -c \
  "SELECT tenant_id, schedule_id, version, due_at, state, delivered_at FROM schedule WHERE tenant_id = 'tenant-manual' AND schedule_id = 'schedule-001';"
```

O estado inicial deve ser `SCHEDULED`. Após `dueAt`, repita a consulta; o estado deve ser `DELIVERED` e `delivered_at` deve estar preenchido. O log da aplicação também deve conter `Fake delivery completed`.

## 7. Testes negativos essenciais

### Identificador duplicado

Repita o POST anterior com o mesmo `tenantId` e `scheduleId`. O resultado esperado é HTTP `409 Conflict`. A unicidade é composta por tenant e schedule.

### Prazo no passado

```bash
curl -i -X POST 'http://localhost:8080/v1/tenants/tenant-manual/schedules' \
  -H 'Content-Type: application/json' \
  -d '{"scheduleId":"schedule-past","dueAt":"2000-01-01T00:00:00Z","payload":{"kind":"invalid"}}'
```

O resultado esperado é HTTP `400 Bad Request`, pois `dueAt` precisa ser futuro no instante em que o caso de uso é executado.

### Campo obrigatório ausente

```bash
curl -i -X POST 'http://localhost:8080/v1/tenants/tenant-manual/schedules' \
  -H 'Content-Type: application/json' \
  -d '{"dueAt":"2030-01-01T12:00:00Z","payload":{}}'
```

O resultado esperado é HTTP `400 Bad Request`, porque `scheduleId` é obrigatório.

## 8. Métricas úteis

Liste as métricas expostas:

```bash
curl -s http://localhost:8080/actuator/metrics
```

Após uma entrega, consulte:

```bash
curl -s http://localhost:8080/actuator/metrics/scheduler.schedules.delivered
curl -s http://localhost:8080/actuator/metrics/scheduler.schedule.lag
curl -s http://localhost:8080/actuator/metrics/scheduler.delivery.duration
```

Não interprete uma execução manual como benchmark; medições de capacidade pertencem aos experimentos definidos em `poc-strategy.md`.

## 9. Encerrar e retomar

Interrompa a aplicação com `Ctrl+C`. Para parar o banco preservando os dados no container:

```bash
docker stop scheduler-postgres
```

Para uma nova sessão, execute `docker start scheduler-postgres`, exporte novamente as três variáveis de datasource e inicie a aplicação. A fila em memória será reconstruída a partir dos schedules persistidos dentro da janela de look-ahead.

## 10. Próximo passo: benchmark de burst

Depois de validar manualmente o fluxo, siga o protocolo [POC-0 — Same-second burst benchmark](experiments/poc-0-burst-benchmark.md). O primeiro warm-up pode ser executado com:

```bash
./scripts/experiments/poc0-burst.sh 100 16 60
```

Esse experimento fica fora da suíte normal e grava seus artefatos em `target/experiments/poc0/`.

## Problemas comuns

- `Connection refused` em `5432`: confirme `docker ps` e `pg_isready`.
- `Bind for 0.0.0.0:5432 failed`: outro PostgreSQL já ocupa a porta; use-o com credenciais adequadas ou mapeie outra porta e ajuste a URL JDBC.
- compilação com versão de Java incompatível: confirme `JAVA_HOME` e `java -version`.
- `409` inesperado ao repetir o guia: use outro `scheduleId` ou outro tenant; schedules entregues continuam persistidos.
- o schedule não muda de estado: confirme que `dueAt` está em UTC e no futuro, observe o log e confira se a aplicação mantém conexão com o banco.
