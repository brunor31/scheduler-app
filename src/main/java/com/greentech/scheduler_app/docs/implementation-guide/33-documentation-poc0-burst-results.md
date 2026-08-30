# Resultado `poc-0-burst-results-2026-08-29.md`

## Papel

Este documento transforma os artefatos temporários de cada execução em evidência durável para decisões arquiteturais. Os CSVs brutos ficam em `target/` e não são versionados; por isso os identificadores, valores e condições relevantes são preservados no registro.

## Consolidação

Cada cenário usa três repetições válidas. A tabela consolidada usa a mediana e conserva a faixa mínimo–máximo, evitando que uma única execução seja apresentada como capacidade estável.

## Tratamento do outlier

A rodada interrompida por sleep não foi apagada nem misturada à baseline. O registro contém o run ID, os números e a evidência do log de energia. Essa separação responde a duas perguntas distintas:

```text
processo continuamente ativo → throughput do worker
host suspenso → comportamento overdue após retomada
```

O segundo caso alimenta o POC-1, mas não mede a capacidade de drenagem contínua do POC-0.

## Encaixe na arquitetura

Os resultados mostram que o `DelayQueue` torna os itens elegíveis, enquanto o caminho serial de read, dispatch e update determina a vazão. Essa conclusão é evidência para um possível executor limitado, mas a mudança depende do objetivo de latência do produto.

## Limite

Os números pertencem ao ambiente local documentado, com target fake e PostgreSQL em Colima. Eles não são SLO de produção nem podem ser extrapolados para outro hardware, target remoto ou múltiplas instâncias.
