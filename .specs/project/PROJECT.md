# Desafio Nota Fiscal — Invoice Generator

**Vision:** Take a legacy invoice-generation service shipped with intentional defects and evolve it into a quality, scalable Java/Spring application with clean architecture, real test coverage, resilience, observability, and a credible deployment proposal for AWS.

**For:** the candidate (challenge submission) and any reviewer evaluating the technical solution. The deployable artifact targets a real microservice scenario: an invoice generator consumed by upstream order systems and integrating with stock, registry, delivery, and finance downstreams.

**Solves:** the README-listed problems — code that's hard to maintain, cross-request state pollution, value inconsistencies, slow processing on orders > 5 items, broken tests, and the lack of a proper architecture proposal for the full flow.

## Goals

- **Functional correctness.** All inputs documented in `docs/business-rules.md` produce the right `Invoice` for the right reasons. Success metric: 100 % of documented business rules covered by passing automated tests; zero known correctness bugs from `CONCERNS.md` C-1…C-4 open at the end of M2.
- **Modern, modular code.** Java 21 + Spring Boot 3.x. Domain logic decoupled from frameworks via Clean Architecture (use cases + adapters). Success metric: domain/use-case layer has zero Spring or Jackson imports; ≥80 % unit-test coverage on that layer.
- **Resilience under slow integrations.** Tail latency for `POST /api/orders/generate-invoice` is bounded regardless of upstream slowness. Success metric: p99 latency ≤ 1500 ms for orders of any size, even with the 5-second delivery upstream sleep simulated.
- **Operability.** The service is deployable to AWS with documented IaC, structured logs, metrics, and distributed tracing. Success metric: a single `terraform apply` (or `cdk deploy`) creates a runnable environment with dashboards and alerts wired up.

## Tech Stack

**Core (target):**

- Framework: Spring Boot **3.x** (latest stable at implementation time)
- Language: **Java 21**
- Database: none required by the domain. If any persistence is added (e.g., outbox table), default to PostgreSQL.

**Key dependencies (target):**

- `spring-boot-starter-web` — HTTP
- `resilience4j-spring-boot3` — timeout / circuit breaker / retry
- `micrometer` + `micrometer-registry-cloudwatch` (or OTel exporter) — metrics
- `opentelemetry-spring-boot-starter` — distributed tracing → X-Ray
- `lombok` (modern version, JDK 21-compatible)
- `spring-boot-starter-test` + `mockito` + `testcontainers` (if/when persistence is introduced)

**IaC:**

- Terraform (default per `STATE.md` AD-005; switchable to CDK if revisited)

## Scope

**v1 includes:**

- All 10 themes raised by the README (`Correção do acúmulo`, `Correção das inconsistências`, `Melhoria da organização`, `Correção e criação de testes`, `Pedidos > 6 itens`, `Java 21 + Spring atualizado`, `Documentação mínima`, `Planejamento de deploy/operação`, `Observabilidade`, `Resiliência`).
- Clean Architecture refactor with explicit use cases and adapters (user-requested, captured as feature **F-CLEAN**).
- IaC for the AWS deployment proposal (Terraform).
- Real test suite (unit on domain/use-case, integration on adapters, end-to-end on HTTP).
- Resilience patterns on outbound integrations (timeouts, circuit breakers, async dispatch for non-critical side effects).

**Explicitly out of scope:**

- Production rollout (this is a challenge / portfolio deliverable, not a launch).
- Authentication of the upstream caller (the gateway-side is **documented** as part of the AWS proposal but **not implemented**).
- A full SEFAZ / fiscal-registry integration. The `RegistrationService` adapter has a contract and a fake; talking to real SEFAZ is beyond this project.
- A UI. The system is API-only.
- Internationalization of business rules. Tax brackets / regions stay Brazilian.

## Constraints

- **Timeline:** none stated by the challenge; goal is correctness + quality over speed.
- **JSON payload contract is locked.** Per README — input/output JSON keys remain snake_case Portuguese, enum values remain Portuguese (FISICA / SIMPLES_NACIONAL / SUDESTE / …). Java identifiers are English (post-rename).
- **The `Thread.sleep` calls must not simply be deleted.** They simulate slow upstream systems and the solution must demonstrate how to *handle* slowness, not erase it.
- **JDK 11 needed until F-UPGRADE lands.** Lombok 1.18.22 (pinned by Spring Boot 2.6.2) is incompatible with JDK 16+. See `CONCERNS.md` C-10 and the `JAVA_HOME` override in `TESTING.md`.
