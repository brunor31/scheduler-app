# `OpenApiConfiguration` e Springdoc

## Papel

`OpenApiConfiguration` define os metadados gerais da API: nome, versão, descrição e servidor relativo. A dependência `springdoc-openapi-starter-webmvc-ui` lê esses metadados e as anotações do controller para gerar o contrato OpenAPI em runtime.

## Por que existe uma classe de infraestrutura

Nome e versão da API são preocupações de exposição HTTP, não regras de scheduling. A classe fica em `infrastructure/config` e não é referenciada pelas camadas de aplicação ou domínio, preservando a direção de dependências.

## Dependência

O `pom.xml` fixa o Springdoc `3.1.0`, linha compatível com Spring Boot 4. O starter fornece tanto o documento OpenAPI quanto os recursos estáticos do Swagger UI; nenhuma segunda implementação do contrato é mantida manualmente.

## Endpoints técnicos

Com a aplicação em execução:

```text
GET /v3/api-docs
GET /v3/api-docs.yaml
GET /swagger-ui.html
GET /swagger-ui/index.html
```

O documento é derivado do código do adapter web. O teste de integração valida a presença da operação e das respostas documentadas para reduzir divergência entre implementação e documentação.

## Limite arquitetural

Swagger descreve o contrato HTTP, mas não aumenta as garantias da plataforma. A semântica permanece at-least-once e o target do POC-0 continua sendo `FAKE`.
