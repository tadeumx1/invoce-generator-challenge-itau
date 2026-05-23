# Desafio Nota Fiscal

## Contexto

A aplicação atual processa notas fiscais, mas apresenta problemas de manutenção, testes, performance e consistência dos dados retornados.

O objetivo do desafio é corrigir os problemas existentes, melhorar a qualidade técnica da solução e propor uma arquitetura adequada para o fluxo completo.

## Problemas atuais

- Código difícil de manter e alterar.
- Regras de cálculo e fluxo de processamento complexos.
- Classe principal instável e com muitas responsabilidades.
- Baixa cobertura de testes, com alguns testes quebrando.
- A primeira execução funciona corretamente, mas as seguintes acumulam itens de execuções anteriores.
- Pedidos com mais de 6 itens ficam muito lentos.
- Após algumas execuções, o retorno também passa a ficar mais lento.
- Outros sistemas relatam inconsistências nos valores da nota e no total de itens.

## Premissas

- O payload de entrada não deve ser modificado.
- A aplicação usa Java 21 e Spring Boot 3.5.x.
- A solução deve corrigir os problemas funcionais e melhorar a manutenção do código.
- Boas práticas de desenvolvimento, testes, documentação e deploy devem ser consideradas.

## Execução local

```bash
./mvnw test
./mvnw verify
./mvnw spring-boot:run
```

O comando `./mvnw verify` é o gate principal: compila em Java 21, executa os testes,
valida formatação/estilo e gera o relatório JaCoCo em `target/site/jacoco/index.html`.

## Simulação de integração lenta

Existe no código uma lógica com `sleep` para simular uma integração externa mais lenta para itens a partir de determinado valor.

Essa lógica faz parte do desafio e **não deve ser simplesmente removida**.

O candidato deve analisar esse comportamento e propor uma solução para o problema.

## O que considerar na solução

O candidato deve considerar:

- Correção do acúmulo indevido de itens entre execuções.
- Correção das inconsistências nos valores da nota e total de itens.
- Melhoria da organização do código e separação de responsabilidades.
- Correção e criação de testes relevantes.
- Melhor tratamento para pedidos com mais de 6 itens.
- Uso adequado de Java 21 e Spring atualizado.
- Documentação mínima para executar, testar e entender a solução.
- Planejamento de deploy e operação da aplicação.
- Observabilidade, incluindo logs, métricas e tracing.
- Estratégias de resiliência para integrações lentas ou instáveis.

## Proposta de arquitetura

Além do código, o candidato deve apresentar uma proposta de desenho arquitetural do fluxo da aplicação.

A proposta deve considerar, quando aplicável:

- API Gateway;
- autenticação/autorização;
- APIs externas ou internas consumidas;
- tratamento de integrações lentas;
- filas ou processamento assíncrono;
- observabilidade;
- estratégia de deploy;
- contexto AWS.

---

## Mapa da solução

A solução foi planejada como uma sequência de features rastreáveis em `.specs/`. Cada feature
tem `spec.md` (requisitos), opcionalmente `design.md` (arquitetura) e `tasks.md` (execução
atômica com gates de teste). O progresso por milestone:

```
M1 — Qualidade (3/3) ✅
  F-SAFETY-NET ✅      F-UPGRADE ✅      F-CLEAN ✅

M2 — Defeitos funcionais (2/2) ✅
  F-DEFECTS-FUNCTIONAL ✅      F-DEFECTS-PERFORMANCE ✅

M3 — Operações (1/3) 🟡
  F-RESILIENCE ✅   F-OBSERVABILITY 🟡 (planejado, ready)   F-AWS 🔴
```

Legenda: ✅ implementado · 🟡 spec/design/tasks frozen, execução pendente · 🔴 planejado.

### Problemas atuais → feature responsável

| Problema do README | Feature que resolve | Status | Onde |
|---|---|---|---|
| Código difícil de manter e alterar | **F-CLEAN** | ✅ | `.specs/features/clean/` — Clean Architecture (domain / application / adapter), ports & adapters |
| Regras de cálculo e fluxo complexos | **F-CLEAN** | ✅ | `switch` dispatch em `TaxRateTable` + `LegacyFreightCalculator` (eliminou `if/else` aninhados) |
| Classe principal instável, muitas responsabilidades | **F-CLEAN** | ✅ | `GenerateInvoiceInteractor` enxuto + ports separados |
| Baixa cobertura de testes (alguns quebrando) | **F-SAFETY-NET** | ✅ | `.specs/features/safety-net/` — 56 testes iniciais; hoje 64 fast + 1 slow. Resolve C-5 (mocks falsos) e C-9 |
| 1ª execução OK, demais acumulam itens | **F-DEFECTS-FUNCTIONAL** | ✅ | Concern **C-1** — `LegacyProductTaxRateCalculator` agora stateless |
| Pedidos > 6 itens muito lentos | **F-DEFECTS-PERFORMANCE** | ✅ | Concern **C-6** — Kafka async dispatch tira o sleep de 5 s do request thread |
| Retorno fica lento após algumas execuções | **F-DEFECTS-FUNCTIONAL** + **F-DEFECTS-PERFORMANCE** | ✅ | C-1 (lista acumulando) + C-6 (sleep fora do request) |
| Inconsistências em valores e total de itens | **F-DEFECTS-FUNCTIONAL** | ✅ | C-2 (regime OUTROS → items vazio), C-3 (frete 0), C-4 (double → BigDecimal HALF_EVEN scale 2) — todos viram HTTP 400 com `codigo` / `mensagem` |

### O que considerar na solução → feature responsável

| Item do README | Feature | Status |
|---|---|---|
| Correção do acúmulo indevido de itens | **F-DEFECTS-FUNCTIONAL** (C-1) | ✅ |
| Correção das inconsistências nos valores | **F-DEFECTS-FUNCTIONAL** (C-2, C-3, C-4) | ✅ |
| Organização do código / separação de responsabilidades | **F-CLEAN** | ✅ |
| Correção e criação de testes relevantes | **F-SAFETY-NET** | ✅ |
| Melhor tratamento para pedidos > 6 itens | **F-DEFECTS-PERFORMANCE** (C-6) | ✅ |
| Java 21 e Spring atualizado | **F-UPGRADE** (Spring Boot 3.5.14) | ✅ |
| Documentação mínima | **transversal** — `README.md`, `CLAUDE.md`, `docs/business-rules.md`, `docs/translation-changelog.md`, todo o `.specs/` | ✅ |
| Planejamento de deploy e operação | **F-DEFECTS-PERFORMANCE T4** (Dockerfile + docker-compose) + **F-AWS** | 🟡 Local pronto; AWS planejado |
| Observabilidade (logs, métricas, tracing) | **F-OBSERVABILITY** | 🟡 Spec + design + tasks frozen, execução pendente |
| Resiliência para integrações lentas/instáveis | **F-DEFECTS-PERFORMANCE T3** (retry/DLT + idempotência) + **F-RESILIENCE** (Resilience4j `@CircuitBreaker` + C-8 fix) | ✅ |

### Proposta de arquitetura → feature responsável

| Item do README | Feature | Status |
|---|---|---|
| API Gateway | **F-AWS** | 🔴 Planejado (documentado no spec, não implementado) |
| Autenticação / autorização | **F-AWS** | 🔴 Planejado (Cognito/JWT documentado apenas, não provisionado — escopo definido em `.specs/project/PROJECT.md`) |
| APIs externas ou internas consumidas | **F-CLEAN** (ports) + **F-DEFECTS-PERFORMANCE** (eventos Kafka) | ✅ Domain ports + 4 tópicos Kafka definidos |
| Tratamento de integrações lentas | **F-DEFECTS-PERFORMANCE** | ✅ Kafka async dispatch + `@RetryableTopic` + DLT |
| Filas / processamento assíncrono | **F-DEFECTS-PERFORMANCE** | ✅ 4 tópicos `invoice.*.v1` + retry topics + DLT, consumers em grupos isolados |
| Observabilidade | **F-OBSERVABILITY** | 🟡 4 SLIs explícitos (success rate, p99 latency, Kafka dispatch, side-effect end-to-end) |
| Estratégia de deploy | **F-DEFECTS-PERFORMANCE T4** + **F-AWS** | 🟡 Dockerfile + docker-compose KRaft prontos; Terraform para ECS/MSK no F-AWS |
| Contexto AWS | **F-AWS** | 🔴 Planejado — API Gateway HTTP → ECS Fargate → MSK + DLQ topics → CloudWatch + X-Ray |

### Onde olhar

- `.specs/project/ROADMAP.md` — sequência de features, status de cada uma e critério de conclusão.
- `.specs/project/STATE.md` — log de decisões arquiteturais (ADRs), blockers resolvidos, lições.
- `.specs/codebase/CONCERNS.md` — defeitos catalogados (C-1..C-10) e qual feature resolve cada um.
- `.specs/codebase/ARCHITECTURE.md` — visão da arquitetura Clean atual.
- `.specs/features/<nome>/` — spec, design e tasks por feature.
- `docs/business-rules.md` — contrato frozen de regras de cálculo (brackets, frete, side effects).
- `CLAUDE.md` — guia operacional do dia-a-dia (comandos, layout, constraints).

### Execução do fluxo Kafka local

Depois de F-DEFECTS-PERFORMANCE, o stack completo sobe com:

```bash
docker compose up --build
```

- App em `http://localhost:8080`
- Kafka externo em `localhost:29092` (para tooling tipo `kafka-console-consumer`)
- Tópicos `invoice.stock-deduction.v1`, `invoice.registration.v1`,
  `invoice.delivery-scheduling.v1`, `invoice.accounts-receivable.v1` (3 partições cada,
  message key = `invoiceId`)
- Retry topics e DLT criados automaticamente via `@RetryableTopic` (`-retry-N`, `-dlt`)
