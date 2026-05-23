# Roadmap

**Current Milestone:** M1 — Quality foundation
**Status:** M2 in progress. F-DEFECTS-FUNCTIONAL complete. Next: F-DEFECTS-PERFORMANCE.

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

**F-UPGRADE — Java 21 + Spring Boot 3.x** — COMPLETE (2026-05-23, Spring Boot 3.5.14 + Java 21; `./mvnw verify` green)

- Bumped `<java.version>` to 21 and parent to `spring-boot-starter-parent` 3.5.14.
- Confirmed no `javax.*` imports existed in app/test code, so no `jakarta.*` migration was needed.
- Spring Boot 3.5.14 dependency management provides a JDK-21-compatible Lombok.
- Added Spotless + google-java-format and a Checkstyle import policy.
- Documented `./mvnw verify` as the build/CI gate in `README` and `CLAUDE.md`.

**F-CLEAN — Clean Architecture refactor: use cases + adapters** (user-requested) — COMPLETE (2026-05-23, `./mvnw verify` + `./mvnw test -Pslow` green)

- Introduced `domain/`, `application/`, and `adapter/` layers.
- Extracted `GenerateInvoiceUseCase` / `GenerateInvoiceInteractor`.
- Defined outbound ports in `domain/port` and adapter implementations in `adapter/integration`.
- Replaced internal `new XxxService()` orchestration with constructor-injected ports wired by `ApplicationBeanConfig`.
- Moved JSON contract annotations to `adapter/web/dto`, keeping Spring/Jackson out of `domain/` and `application/`.
- Replaced tax-rate nested `if/else` chains with `switch` dispatch and reusable bracket tables.
- Replaced the freight multiplier `if/else` ladder with a `switch` and made delivery-region extraction null-safe.
- Preserved known legacy defects for M2 characterization: C-1, C-2, C-4, C-6, and the C-3 missing-region policy gap. The null-region NPE path was removed; null region now follows the existing freight `0.0` fallback.

---

## M2 — Functional defect closure

**Goal:** every functional defect in `CONCERNS.md` C-1…C-4 closed, then the remaining slow-path defect C-6 addressed with an operationally safe async/resilience design.
**Target:** completion of F-DEFECTS-FUNCTIONAL, F-DEFECTS-PERFORMANCE.

### Features

**F-DEFECTS-FUNCTIONAL — Close C-1, C-2, C-3, C-4** — COMPLETE (2026-05-23, 56 fast tests + slow profile + `./mvnw verify` green)

- C-1: `LegacyProductTaxRateCalculator` is stateless; every call returns a fresh invoice-item list.
- C-2: `JURIDICA + OUTROS` and null tax regime now reject with HTTP 400 (`UNSUPPORTED_TAX_REGIME` / `INVALID_TAX_REGIME`).
- C-3: missing delivery address or null delivery region now rejects with HTTP 400 (`INVALID_DELIVERY_REGION`).
- C-4: domain and DTO monetary fields now use `BigDecimal`; calculated tax and freight round to scale 2 with `HALF_EVEN`. JSON stays numeric on the wire.

**F-DEFECTS-PERFORMANCE — Close the +5s-on-6-items trap (C-6)** — PLANNED

- Move stock, finance, and (likely) delivery dispatch off the request thread. Do **not** use detached fire-and-forget threads as the production answer; implement an outbox-style durable queue pattern: persist the intent, return 200 OK, and let a worker drain.
- `CompletableFuture.runAsync(...)` is acceptable only as a transitional/local implementation if explicitly documented with its limitations: errors can disappear, retry is absent, work can be lost on process shutdown, and tracing is weak.
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
