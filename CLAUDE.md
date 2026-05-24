# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context

This is the **Desafio Nota Fiscal** — a Brazilian invoice generator coding challenge. The codebase intentionally contains defects that are being addressed through spec-driven tasks: safety net, Java/Spring upgrade, Clean Architecture, functional fixes, resilience/observability, and AWS architecture.

Read `README.md` and `docs/business-rules.md` before changing behavior. `docs/business-rules.md` is the frozen contract for tax brackets, freight, side effects, and known defects.

## Hard Constraints

- **Do not modify the input/output JSON payload.** JSON keys remain snake_case Portuguese (`id_pedido`, `valor_total_itens`, `tipo_pessoa`, ...), and enum values remain Portuguese (`FISICA`, `SIMPLES_NACIONAL`, `SUDESTE`, `ENTREGA`, ...). The JSON contract is isolated in `adapter/web/dto`.
- **Do not simply delete `Thread.sleep` calls.** They simulate slow/asynchronous external integrations. Future work should handle them with Kafka dispatch, retry/DLQ, timeouts, and resilience.
- The active stack is **Java 21 + Spring Boot 3.5.14**. Use the default JDK 21 shell; no `JAVA_HOME` override is required.

## Commands

```bash
./mvnw clean package          # full build + tests
./mvnw test                   # fast tests only
./mvnw verify                 # tests + Spotless + Checkstyle + JaCoCo
./mvnw spring-boot:run        # run the app on port 8080

./mvnw test -Dtest=GenerateInvoiceInteractorTest
./mvnw test -Dtest=TaxRateSelectionFisicaTest

./mvnw test -Pslow            # slow characterization tests
./mvnw spotless:apply         # format Java sources
```

Exercising the API locally (F-AUTH requires a JWT):

```bash
TOKEN=$(curl -sS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123"}' | jq -r .access_token)

curl -X POST http://localhost:8080/api/orders/generate-invoice \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d @src/main/resources/payloads/teste-pf.json
```

Demo users: `demo`/`demo123` (scope `invoice:write`) and `admin`/`admin123` (scope `invoice:write invoice:admin`).
See [`docs/auth-strategy.md`](docs/auth-strategy.md) and AD-032 in `STATE.md` for the rationale (intentionally
diverges from the edge-validates production recommendation).

C-7 closed (2026-05-23): the sample directory is now `payloads/` (was `paylods/`); every doc, test, and spec reference has been updated.

## Architecture

Single Spring Boot module, package root `br.com.itau.invoicegenerator`, organized as Clean Architecture.

```
adapter/web/InvoiceController
        |
        v
application/GenerateInvoiceUseCase
application/GenerateInvoiceInteractor
        |
        +-- domain/service/TaxRateTable
        +-- domain/port/TaxRateCalculator
        +-- domain/port/FreightCalculator
        +-- domain/port/StockPort
        +-- domain/port/InvoiceRegistrationPort
        +-- domain/port/DeliveryPort
        +-- domain/port/AccountsReceivablePort
              ^
              |
adapter/integration/* implementations
```

Layer rules:

- `domain/` and `application/` must not import Spring or Jackson.
- JSON DTOs and `@JsonProperty` live in `adapter/web/dto`.
- Spring bean composition lives in `adapter/config/ApplicationBeanConfig`.
- Simulated downstream systems live under `adapter/integration/{stock,registration,delivery,finance}`.

## Defect Status

F-RESILIENCE closed C-8 and added circuit breakers:

- `IntegrationAdapterException` (typed `RuntimeException`) replaces every raw `RuntimeException` wrap around `InterruptedException` in `adapter/integration/**`. The interrupt flag is now preserved (`Thread.currentThread().interrupt();`).
- `@CircuitBreaker(name="<port>")` annotates each outbound adapter method (Stock, InvoiceRegistration, Delivery, AccountsReceivable). Per-port thresholds live in `application.properties` under `resilience4j.circuitbreaker.instances.<name>.*`.
- `@TimeLimiter` is intentionally deferred (would force `CompletableFuture` on every port). Documented in AD-027.

F-DEFECTS-FUNCTIONAL resolved the first correctness batch:

- `LegacyProductTaxRateCalculator` is stateless per call (C-1 fixed).
- JURIDICA + `taxRegime = OUTROS`/null rejects with HTTP 400 (C-2 fixed).
- Missing delivery address or delivery address with `region=null` rejects with HTTP 400 (C-3 fixed).
- Money uses `BigDecimal`; calculated tax/freight round to scale 2 with `HALF_EVEN` (C-4 fixed).

F-DEFECTS-PERFORMANCE closed C-6 with Kafka async dispatch:

- `GenerateInvoiceInteractor` depends on `InvoiceSideEffectDispatcher` (domain port). The Kafka adapter publishes four `IntegrationEvent` JSON envelopes (one per topic: `invoice.stock-deduction.v1`, `invoice.registration.v1`, `invoice.delivery-scheduling.v1`, `invoice.accounts-receivable.v1`) and returns; HTTP success means *generated + dispatch accepted*, not "downstreams completed".
- Four `@KafkaListener` consumers (one per integration, separate group IDs) call the existing port adapters. The 5-second delivery sleep stays in the consumer.
- `@RetryableTopic` (attempts=4, exponential backoff with `app.kafka.retry.*` properties) wires retry topics + a `-dlt` topic per integration. Auto-created on startup.
- In-memory `IdempotencyStore` keyed on `(topic, eventId)` dedupes Kafka redelivery. **Not durable** — production needs Redis/Postgres (see `AD-024`).
- Local stack: `docker compose up --build` starts `cp-kafka` (KRaft) + the app; HTTP on 8080, Kafka external listener on host 29092.
- Tests: `InvoiceKafkaFlowIntegrationTest` uses `@EmbeddedKafka` for end-to-end proof. `InvoiceControllerIntegrationTest` and `InvoiceGeneratorApplicationTests` set `app.messaging.kafka.enabled=false` plus `NoOpKafkaTestConfig` to skip Kafka context wiring.

## Testing Notes

The main safety net is 56 fast tests plus the slow profile on demand:

- `./mvnw test`: fast suite, excludes `@Tag("slow")`.
- `./mvnw test -Pslow`: slow delivery characterization.
- `./mvnw verify`: full pre-commit gate.

No current tests use Mockito; it is excluded from the test starter to keep Spring tests runnable in restricted JVM environments.

## AWS deployment proposal (F-AWS, complete)

Reviewer-facing architecture write-up (diagram, services table, ADRs, cost, runbook):
[`docs/aws-architecture.md`](docs/aws-architecture.md). Terraform modules under
[`infra/terraform/`](infra/terraform/). Gate: `terraform fmt -recursive -check + init
-backend=false + validate` (proposal-grade — validates clean, not applied against a
real account).

## Observability (F-OBSERVABILITY, complete)

Operator-facing reference (SLI catalog, Prometheus queries, runbook):
[`docs/observability.md`](docs/observability.md).

The spec at `.specs/features/observability/spec.md` freezes four SLIs that every Micrometer
counter, timer, and histogram in this codebase exists to serve:

1. **SLI-1 API success rate** — `http.server.requests` (status ≠ 5xx). SLO: 99.5 % / 30d.
2. **SLI-2 API latency** — `http.server.requests` histogram, SLO buckets at 300 ms / 800 ms / 2 s. SLO: 99 % < 800 ms.
3. **SLI-3 Kafka dispatch success** — `invoice.dispatch{outcome}` counter. SLO: 99.9 % / 7d.
4. **SLI-4 Side-effect end-to-end latency** — `invoice.sideeffect.duration` timer (producer → consumer-ack). SLO: 95 % < 30s per integration.

Rules for any future metric / log / trace work:

- **SLIs live in the backend, not in code.** The application emits raw counters / timers /
  histogram buckets only. Prometheus or CloudWatch computes the ratio.
- **`orderId`, `invoiceId`, `correlationId`, `traceId`, and `spanId` are never metric tags.**
  They go on logs (via MDC) and on trace attributes only — see AD-020 in `STATE.md` and the
  cardinality table in the spec.
- **JSON logs only.** Use the configured `logstash-logback-encoder` setup; no
  `System.out.println`, no plain-text logger calls.
- **Local vs AWS.** Local Docker Compose uses Prometheus scrape + OTLP → Jaeger; AWS uses
  the Micrometer CloudWatch registry + ADOT collector → X-Ray (AD-018). Spring profile
  picks the registry/exporter; instrumentation code is the same.
