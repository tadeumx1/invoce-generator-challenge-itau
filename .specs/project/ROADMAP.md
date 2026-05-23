# Roadmap

**Current Milestone:** M1 — Quality foundation
**Status:** In Progress (F-SAFETY-NET complete; F-UPGRADE next)

This roadmap reflects the user-confirmed sequencing: **safety net → upgrade → Clean Architecture → defect fixes → operations**. Each feature has an ID used everywhere else (`CONCERNS.md`, spec files, commit messages).

---

## M1 — Quality foundation

**Goal:** make the codebase safe to refactor. Real tests, modern toolchain, Clean Architecture in place. At the end of M1 nothing visible to the API has changed; everything underneath has.
**Target:** completion of features F-SAFETY-NET, F-UPGRADE, F-CLEAN (in that order).

### Features

**F-SAFETY-NET — Real test suite covering the documented business rules** — COMPLETE (2026-05-22, 53 tests passing + 1 slow on-demand; JaCoCo HTML report wired)

- Unit tests for every tax bracket × person-type × tax-regime combination, with explicit assertions at the bracket edges (500 / 1000 / 2000 / 3500 / 5000).
- Unit tests for freight multiplier per region (5 cases) and the missing-region fallthrough (currently zeros — keep red, fix in M2).
- A characterization test for the static-list accumulation bug (asserts the bug, will flip to assert-no-leak in M2).
- An integration test through `InvoiceController` end-to-end with the two `paylods/` JSON fixtures.
- Drop the misleading `@Mock` in `InvoiceGeneratorServiceImplTest` (C-5). Wire the calculator as a constructor dep on the service so it's actually mockable.

**F-UPGRADE — Java 21 + Spring Boot 3.x** — PLANNED

- Bump `<java.version>` to 21; bump parent to `spring-boot-starter-parent` 3.x latest stable.
- Migrate `javax.*` → `jakarta.*` (Servlet, Validation if added).
- Pull a JDK-21-compatible Lombok version (unblocks the toolchain mess in C-10).
- Add `spotless` (or `google-java-format` Maven plugin) and a `checkstyle` policy so future style drift is mechanical.
- Add CI command (`./mvnw verify`) and document in `README` / `CLAUDE.md`.

**F-CLEAN — Clean Architecture refactor: use cases + adapters** (user-requested) — PLANNED

- Introduce three layers: `domain/` (pure types, business rules, ports), `application/` (use cases / interactors), `adapter/` (`adapter/web/`, `adapter/integration/{stock,registration,delivery,finance}`, `adapter/persistence/` only if added).
- Extract `GenerateInvoiceUseCase` as the single application entry; it consumes outbound ports defined in `domain/` (`TaxRateCalculator`, `FreightCalculator`, `StockPort`, `InvoiceRegistrationPort`, `DeliveryPort`, `AccountsReceivablePort`).
- Replace every `new XxxService()` with constructor-injected adapter implementations.
- Spring stays out of `domain/` and `application/`. Spring annotations live only in `adapter/`.
- Tax-rate selection moves to a `TaxRateTable` strategy per `PersonType × CompanyTaxRegime` (kills the giant if/else in C-2's vicinity, even though defect resolution itself is M2).
- Freight selection moves to a `FreightCalculator` strategy per `Region`.

---

## M2 — Functional defect closure

**Goal:** every defect in `CONCERNS.md` C-1…C-4 closed, with tests that would have caught them.
**Target:** completion of F-DEFECTS-FUNCTIONAL, F-DEFECTS-PERFORMANCE.

### Features

**F-DEFECTS-FUNCTIONAL — Close C-1, C-2, C-3, C-4** — PLANNED

- C-1: Make `TaxRateCalculator` (or the use case that consumes it) per-request stateless. Drop the static list.
- C-2: Define behavior for `CompanyTaxRegime ∈ {OUTROS, null}` on JURIDICA — decision required (reject vs default). Implement.
- C-3: Define behavior for missing delivery region — decision required (reject vs pass-through). Implement.
- C-4: Migrate domain monetary types to `BigDecimal` with explicit rounding mode. JSON stays as numbers on the wire.

**F-DEFECTS-PERFORMANCE — Close the +5s-on-6-items trap (C-6)** — PLANNED

- Move stock, finance, and (likely) delivery dispatch off the request thread. Implement an outbox-style pattern: persist the intent, return 200 OK, and let a worker drain.
- Keep registration (legally critical) synchronous but bound with a timeout + circuit breaker.
- Verify p99 latency for orders with > 5 items collapses from > 5500 ms to under 1500 ms.

---

## M3 — Operations

**Goal:** the service is deployable, observable, and resilient under failure.
**Target:** completion of F-RESILIENCE, F-OBSERVABILITY, F-AWS.

### Features

**F-RESILIENCE — Resilience4j on outbound adapters** — PLANNED

- Timeout + retry-with-backoff + circuit breaker on every outbound port.
- Per-adapter SLA configured in `application.yml`; documented in `INTEGRATIONS.md`.
- Fallback behavior defined for each (e.g., DeliveryPort failing puts the invoice on an outbox for retry; doesn't fail the request).
- Resilience metrics exported (see F-OBSERVABILITY).

**F-OBSERVABILITY — Logs, metrics, tracing** — PLANNED

- Structured JSON logs (Logback encoder), correlation IDs via MDC, `traceId` / `spanId` enrichment.
- Micrometer metrics for: request rate / latency / errors, per-adapter latency / failure / circuit-breaker state, queue depth (outbox), business metrics (invoices generated by tax regime).
- OpenTelemetry tracing exported to AWS X-Ray.

**F-AWS — AWS deployment proposal + Terraform IaC** — PLANNED

- Architecture diagram (ADR + Mermaid): API Gateway → ECS Fargate (default) or Lambda (alt) → SQS for outbox drain → CloudWatch (logs + metrics) → X-Ray (traces).
- Terraform module(s) under `infra/terraform/` provisioning: VPC, ECS cluster + service + task def, API Gateway HTTP API, SQS queues (work + DLQ), IAM roles, CloudWatch log groups, dashboards, alarms.
- AuthN/AuthZ documented at the gateway boundary (Cognito or JWT verifier) — *documented only*, not provisioned, per scope.

---

## Future Considerations

- Real SEFAZ / fiscal registry integration (today only stubbed).
- Multi-tenant invoice numbering / idempotency keys (`Idempotency-Key` header).
- Webhook receiver for delivery system callbacks.
- Rename `src/main/resources/paylods/` → `payloads/` (C-7).
- Coverage gate in CI (e.g., JaCoCo + minimum thresholds per layer per `TESTING.md`).
- Auth implementation if scope expands.
