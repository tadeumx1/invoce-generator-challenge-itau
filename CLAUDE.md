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

F-BULKHEAD added semaphore bulkheads on the same four adapters (M6, 2026-05-24):

- `@Bulkhead(name="<port>")` lives **alongside** `@CircuitBreaker` on each adapter method. Same instance name; same `application.properties` shape under `resilience4j.bulkhead.instances.<name>.*`.
- Calibration: `deliveryPort.max-concurrent-calls=5` (tighter, because `Thread.sleep(5000)` holds permits for 5s), `stock=registration=accountsReceivable=20`. `max-wait-duration=0` everywhere — fail-fast, rejected calls bubble to `@RetryableTopic`.
- `SEMAPHORE` variant only — `THREADPOOL` would force `CompletableFuture<T>` on every port (same trade-off AD-027 rejected for `@TimeLimiter`).
- Operator-facing doc: [`docs/bulkhead-strategy.md`](docs/bulkhead-strategy.md) — supermarket-checkout analogy, calibration table, AD-033 decision log. `resilience4j-micrometer` auto-publishes `resilience4j.bulkhead.available.concurrent.calls{name}` on `/actuator/prometheus`.

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

## Rate limiting (F-RATELIMIT, complete)

Per-IP rate limiting via `resilience4j-ratelimiter` (M5, 2026-05-24):

- `OncePerRequestFilter` (`RateLimitFilter`) wired into the existing `SecurityFilterChain` via `addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)` so abuse traffic is rejected **before** any JWT validation cost is paid.
- Three statically-named groups in `application.properties` under `resilience4j.ratelimiter.instances.*`: `auth-login` (5/min — brute-force defence), `invoice-generate` (30/min — canonical + legacy alias share the bucket), `default` (60/min — catch-all so any future `/api/**` endpoint inherits a limit). `/actuator/**` is **exempt** so Prometheus scrape + k8s probes are never falsely throttled. `timeout-duration=0` everywhere (fail-fast).
- Per-IP isolation: `ClientIpResolver` resolves IP from `X-Forwarded-For` first hop with `getRemoteAddr()` fallback and an `"unknown"` sentinel. The filter synthesises a per-`(group, ip)` `RateLimiter` via `registry.rateLimiter(group + ":" + ip, prototype.getRateLimiterConfig())` — registry lookup is a thread-safe `ConcurrentHashMap` get.
- 429 contract: `HTTP 429` + `{"codigo":"RATE_LIMIT_EXCEEDED","mensagem":"..."}` + `Retry-After: <ceil(refresh-period)>` integer-seconds header. Same `{codigo, mensagem}` envelope F-AUTH + F-DEFECTS-FUNCTIONAL use. `ApiExceptionHandler.handleRequestNotPermitted` covers any future `@RateLimiter` annotation usage (defence-in-depth).
- Cardinality guard: `RateLimiterMeterFilter` (Micrometer `MeterFilter`) denies any `resilience4j.ratelimiter.*` meter whose `name` tag is not one of the three statically-named instances — keeps the per-IP synthetic instances from publishing one time-series per unique IP (AD-020 budget preserved). `RateLimitMetricsIntegrationTest` proves it via a scrape-based regex check for `:` in the `name` tag.
- Tests: 6 real-chain integration tests in `RateLimitIntegrationTest` (distinct synthetic XFF IPs per method so buckets don't leak across tests) + 2 scrape assertions in `RateLimitMetricsIntegrationTest` + 8 unit (`ClientIpResolverTest`) + 12 unit (`RateLimitPolicyTest`). `AuthControllerIntegrationTest` raises the test-profile `auth-login.limit-for-period` to `10000` via `@TestPropertySource` so its six login calls don't falsely trip prod's 5/min ceiling. **Don't add per-IP tags to metrics** — see AD-020.
- AD-035 in `STATE.md`; operator-facing summary in `docs/business-rules.md` (Rate limiting section) and `docs/observability.md` (Rate-limit signals sub-section). Postman/Newman regression: `07 - Auth — RATE_LIMIT_EXCEEDED on 6th attempt` request, pre-request primes the bucket from `X-Forwarded-For=10.99.0.1`.

## API documentation (F-API-DOCS, complete)

OpenAPI 3 + Swagger UI for the three productive endpoints (M6, 2026-05-24):

- Spec: `GET http://localhost:8080/v3/api-docs` (JSON), `GET /v3/api-docs.yaml` (YAML).
- Interactive UI: `GET http://localhost:8080/swagger-ui.html`.
- Authentication is documented as the `bearer-jwt` HTTP security scheme (HS256 JWT). Click "Authorize" in Swagger UI, paste a token from `POST /api/auth/login`, and the `Authorization: Bearer ...` header attaches to every operation. The login endpoint opts out via `@SecurityRequirements({})` so the chicken-and-egg loop is broken.
- `SecurityConfig` permits `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`; F-RATELIMIT already does not throttle them (they live outside `/api/**`).
- DTO field semantics still live in [`docs/business-rules.md`](docs/business-rules.md) — Swagger UI shows the field tree; the prose stays in the SSOT. AD-034 records the trade-off.

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

## Observability (F-OBSERVABILITY, complete; F-DEBUG-LOGS layered on top)

Operator-facing reference (SLI catalog, Prometheus queries, runbook, **debug logs
catalog**): [`docs/observability.md`](docs/observability.md).

F-DEBUG-LOGS adds structured `info`/`debug`/`warn` log coverage across the HTTP
request path (controller, interactor, tax-bracket, freight, outbound adapters, Kafka
publisher, Resilience4j events, rate-limit trips). Runtime log level for the
`br.com.itau.invoicegenerator` package is controlled by env var `APP_LOG_LEVEL`
(default `INFO`); `POST /actuator/loggers/br.com.itau.invoicegenerator` flips it
without a restart. See [`docs/observability.md`](docs/observability.md) §"Debug logs
catalog" for the per-logger contract and AD-037 in `STATE.md` for the decision log.

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
