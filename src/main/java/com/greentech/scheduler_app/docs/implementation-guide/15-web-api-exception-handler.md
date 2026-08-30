# `ApiExceptionHandler`

## Papel

`ApiExceptionHandler` traduz erros significativos das camadas internas para respostas HTTP baseadas em `ProblemDetail`.

Mapeamentos atuais:

```text
InvalidScheduleException       → 400 Bad Request
ScheduleAlreadyExistsException → 409 Conflict
```

## Encaixe arquitetural

A tradução pertence ao adapter web porque status HTTP é um detalhe de transporte. As exceções de domínio e aplicação não conhecem códigos HTTP.

Essa classe também impede que controllers individuais repitam o mesmo tratamento e mantém o caminho feliz do endpoint legível.

## Evolução esperada

Not found, version conflict e falhas específicas de update/cancel serão adicionados quando esses casos de uso existirem. Erros inesperados continuam sob o tratamento padrão do Spring e não devem ser mascarados como erros de cliente.
