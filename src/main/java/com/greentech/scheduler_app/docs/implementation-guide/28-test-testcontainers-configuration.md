# `TestcontainersConfiguration`

## Papel

`TestcontainersConfiguration` fornece um PostgreSQL efêmero para testes de integração e para a inicialização local de teste.

O `@ServiceConnection` permite que o Spring Boot derive automaticamente URL, usuário e senha do container.

## Imagem

A configuração usa:

```text
postgres:17-alpine
```

A versão foi fixada para impedir que mudanças futuras de `postgres:latest` alterem silenciosamente o ambiente dos testes.

## Encaixe na estratégia

Os testes validam os comportamentos PostgreSQL contra PostgreSQL. A configuração não é carregada na aplicação de produção.

## Nota sobre Colima

No ambiente local atual, o socket é `~/.colima/default/docker.sock`. Quando o Ryuk não consegue remontar esse socket, a suíte pode ser executada com `TESTCONTAINERS_RYUK_DISABLED=true`; nesse caso é importante confirmar que os containers foram encerrados.
