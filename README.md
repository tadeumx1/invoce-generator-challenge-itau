# Invoice Generator

Spring Boot service that takes an order (`Order`) and returns a calculated invoice
(`Invoice`) — with per-item tax computed by bracket, freight adjusted per Brazilian
region, fiscal validation, and asynchronous dispatch of the four back-office
integrations (stock deduction, fiscal registration, delivery scheduling, accounts
receivable) over Kafka.

Origin: the codebase started as a technical-challenge project with intentional defects
(cross-request state leakage, `double` for money, incomplete tax rules, requests with
more than 6 items blocking for 5 s, missing tests). The original challenge brief and the
mapping of which features resolved each item is preserved in
**[`README-CHALLENGE.md`](README-CHALLENGE.md)**.

---

## Stack

| Layer | Technology |
| --- | --- |
| Language | **Java 21** |
| Framework | **Spring Boot 3.5.14** |
| Build | Maven (via `./mvnw`) — Surefire 3.x, JaCoCo 0.8.11 |
| Style | Spotless + google-java-format + Checkstyle (bound to `verify`) |
| Messaging | Spring Kafka — `@KafkaListener`, `@RetryableTopic`, KRaft locally |
| Container | Multi-stage Dockerfile (`eclipse-temurin:21-jdk` builder + `21-jre` runtime) |
| Local compose | Confluent `cp-kafka:7.7` in KRaft mode (no Zookeeper) |
| Tests | JUnit 5 — 64 fast + 1 slow; EmbeddedKafka for the end-to-end flow |

### Main dependencies (`pom.xml`)

| Coordinate | Purpose |
| --- | --- |
| `org.springframework.boot:spring-boot-starter-web` | HTTP + Jackson |
| `org.springframework.kafka:spring-kafka` | Producer, consumer, retry topics |
| `org.projectlombok:lombok` | Getter/setter/builder boilerplate |
| `org.springframework.boot:spring-boot-starter-test` | JUnit 5 + Spring Test + MockMvc |
| `org.springframework.kafka:spring-kafka-test` | `@EmbeddedKafka` for integration tests |

Mockito is excluded from the test starter (the suite uses hand-written test doubles and
fakes — no mocks). Spring Boot and the whole Spring Kafka family are managed by the
`spring-boot-starter-parent`.

---

## Running

### Prerequisites

- JDK 21 (no `JAVA_HOME` override needed — use the default shell).
- Docker + Docker Compose, **only** if you want to exercise the local Kafka flow.

### Maven commands

```bash
./mvnw test                # fast suite (64 tests) — excludes @Tag("slow")
./mvnw test -Pslow         # runs only the slow C-6 characterization
./mvnw verify              # primary gate: tests + Spotless + Checkstyle + JaCoCo
./mvnw spring-boot:run     # boot the app on port 8080
./mvnw spotless:apply      # reformat sources (google-java-format)
./mvnw clean package       # build the jar
```

**`./mvnw verify`** is the official gate — it compiles against Java 21, runs every
test, enforces Spotless + Checkstyle, and emits the JaCoCo report at
`target/site/jacoco/index.html`.

### Hitting the API locally

```bash
./mvnw spring-boot:run &
sleep 8
curl -X POST http://localhost:8080/api/orders/generate-invoice \
  -H 'Content-Type: application/json' \
  -d @src/main/resources/paylods/teste-pf.json
```

> The `paylods/` directory is intentionally misspelled (C-7, deferred). Keep the path
> as is until C-7 is swept.

The legacy URL `POST /api/pedido/gerarNotaFiscal` is still served as an alias for
backwards compatibility.

### Full Kafka flow via Docker

```bash
docker compose up --build
```

Brings up three containers:

- **`invoice-kafka`** — Confluent `cp-kafka:7.7` in KRaft mode. Internal listener
  `kafka:9092` for the app; external listener on `localhost:29092` for host-side tooling
  (`kafka-console-consumer` and friends).
- **`invoice-jaeger`** — `jaegertracing/all-in-one`. OTLP HTTP receiver on `:4318`
  (consumed by the app), Jaeger UI on `localhost:16686`.
- **`invoice-generator`** — the Spring Boot app, exposed on `localhost:8080`
  (HTTP) and `localhost:8080/actuator/prometheus` (Prometheus scrape).

The four topics `invoice.stock-deduction.v1`, `invoice.registration.v1`,
`invoice.delivery-scheduling.v1`, and `invoice.accounts-receivable.v1` are auto-created
on startup (3 partitions, message key = `invoiceId`). Retry topics (`-retry-0/-1/-2`)
and `-dlt` are created on demand by `@RetryableTopic`.

Inspecting the published events:

```bash
docker compose exec invoice-kafka \
  kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic invoice.delivery-scheduling.v1 --from-beginning
```

### Observability — local exploration

```bash
# Drive some traffic
curl -s -H 'X-Correlation-Id: probe-1' \
  -X POST http://localhost:8080/api/orders/generate-invoice \
  -H 'Content-Type: application/json' \
  -d @src/main/resources/paylods/teste-pf.json

# Scrape the four SLI source meters
curl -s http://localhost:8080/actuator/prometheus | grep -E '^(http_server_requests|invoice_)'

# Open the trace (HTTP → invoice.generate → 4 × Kafka producer spans)
open http://localhost:16686
```

Full SLI catalog with Prometheus queries and per-SLI runbook entries:
[`docs/observability.md`](docs/observability.md).

---

## Architecture — Clean Architecture with async Kafka dispatch

```
                     ┌──────────────────────────┐
HTTP POST ──────────▶│ adapter/web/             │
                     │   InvoiceController       │
                     │   ApiExceptionHandler     │
                     └────────────┬─────────────┘
                                  │ (DTO → domain)
                                  ▼
                  ┌───────────────────────────────────┐
                  │ application/                       │
                  │   GenerateInvoiceUseCase           │
                  │   GenerateInvoiceInteractor        │
                  └────────┬────────────┬─────────────┘
                           │            │
                           ▼            ▼
                ┌────────────────┐  ┌──────────────────────────┐
                │ domain/        │  │ domain/port/             │
                │   TaxRateTable │  │   InvoiceSideEffect      │
                │   Legacy*Calc  │  │   Dispatcher (port)       │
                └────────────────┘  └────────────┬─────────────┘
                                                  │
                                                  ▼
                                     ┌────────────────────────────────┐
                                     │ adapter/messaging/              │
                                     │   IntegrationEvent envelope     │
                                     │   IntegrationEventPublisher     │
                                     │   KafkaInvoiceSideEffectDispatch│
                                     │   IdempotencyStore              │
                                     └────────────┬───────────────────┘
                                                  │ (4 topics)
                                                  ▼
                                     ┌────────────────────────────────┐
                                     │ adapter/integration/<bounded>/  │
                                     │   @KafkaListener consumers      │
                                     │      → StockPort                │
                                     │      → InvoiceRegistrationPort  │
                                     │      → DeliveryPort             │
                                     │      → AccountsReceivablePort   │
                                     └────────────────────────────────┘
```

Layer rules:

- `domain/` and `application/` must not import Spring or Jackson.
- JSON DTOs and `@JsonProperty` live in `adapter/web/dto`.
- Spring bean wiring lives in `adapter/config/ApplicationBeanConfig` and
  `adapter/messaging/KafkaMessagingConfig`.
- Each "external system" (stock, registration, delivery, finance) lives under
  `adapter/integration/{stock,registration,delivery,finance}` — one port adapter plus
  one Kafka consumer per integration.

Full architecture write-up: [`.specs/codebase/ARCHITECTURE.md`](.specs/codebase/ARCHITECTURE.md).

---

## Business rules (summary)

The canonical contract lives in [`docs/business-rules.md`](docs/business-rules.md). Quick
recap below.

### Endpoint

`POST /api/orders/generate-invoice` (legacy alias: `POST /api/pedido/gerarNotaFiscal`).
Accepts `Order` JSON, returns `Invoice` JSON. Payload keys are **snake_case Portuguese**
(`id_pedido`, `valor_total_itens`, `tipo_pessoa`, …) and enum values stay in
**Portuguese uppercase** (`FISICA`, `SIMPLES_NACIONAL`, `SUDESTE`, `ENTREGA`). **This
contract is frozen.**

### Per-item tax

```
itemTaxValue = round(unitPrice × taxRate, scale=2, HALF_EVEN)
```

`taxRate` is selected by `(personType, taxRegime, totalItemsValue)`:

| Person | Regime | `totalItemsValue` brackets → rate |
| --- | --- | --- |
| `FISICA` | — | < 500 → 0% · 500–2000 → 12% · 2000–3500 → 15% · > 3500 → 17% |
| `JURIDICA` | `SIMPLES_NACIONAL` | < 1000 → 3% · 1000–2000 → 7% · 2000–5000 → 13% · > 5000 → 19% |
| `JURIDICA` | `LUCRO_REAL` | < 1000 → 3% · 1000–2000 → 9% · 2000–5000 → 15% · > 5000 → 20% |
| `JURIDICA` | `LUCRO_PRESUMIDO` | < 1000 → 3% · 1000–2000 → 9% · 2000–5000 → 16% · > 5000 → 20% |

Bracket edges: the lower bound of the lowest bracket is `<` and the upper bound of the
middle brackets is `≤` — `totalItemsValue = 2000` for `FISICA` falls into the 12%
bracket, **not** 15%. This is frozen legacy behavior; do not "normalize" it.

`JURIDICA + OUTROS` or `JURIDICA + null` → HTTP 400 (`UNSUPPORTED_TAX_REGIME` or
`INVALID_TAX_REGIME`).

### Freight adjustment

```
adjustedFreight = round(freightValue × multiplier(region), scale=2, HALF_EVEN)
```

| Region | Multiplier |
| --- | --- |
| `NORTE` | 1.080 |
| `NORDESTE` | 1.085 |
| `CENTRO_OESTE` | 1.070 |
| `SUDESTE` | 1.048 |
| `SUL` | 1.060 |

The region comes from the first address whose `purpose` is `ENTREGA` or
`COBRANCA_ENTREGA`. Missing delivery address or `region = null` → HTTP 400
(`INVALID_DELIVERY_REGION`).

### Side effects (asynchronous via Kafka)

After computing the `Invoice`, the use case publishes four `IntegrationEvent` messages
to Kafka:

| Topic | Consumer group | Port consumed |
| --- | --- | --- |
| `invoice.stock-deduction.v1` | `invoice-generator-stock-deduction` | `StockPort` |
| `invoice.registration.v1` | `invoice-generator-registration` | `InvoiceRegistrationPort` |
| `invoice.delivery-scheduling.v1` | `invoice-generator-delivery-scheduling` | `DeliveryPort` |
| `invoice.accounts-receivable.v1` | `invoice-generator-accounts-receivable` | `AccountsReceivablePort` |

Each topic has 3 partitions, keyed by `invoiceId`. **HTTP 200 means "invoice computed
and dispatch accepted"** — *not* "downstream systems have completed". This is the
semantic contract delivered by F-DEFECTS-PERFORMANCE.

Transient consumer failures are re-delivered via `@RetryableTopic` (4 attempts,
exponential backoff 60 s × 5.0 → 1m → 5m → 25m → DLT). `IdempotencyStore` (in-memory,
keyed on `(topic, eventId)`, mark-after-success) guards against double execution on
redelivery. **Not durable** — production needs Redis or a database.

### Input validation (HTTP 400)

| Scenario | Code |
| --- | --- |
| `JURIDICA + OUTROS` | `UNSUPPORTED_TAX_REGIME` |
| `JURIDICA` without `taxRegime` | `INVALID_TAX_REGIME` |
| Missing delivery address / `region = null` | `INVALID_DELIVERY_REGION` |

Response JSON: `{ "codigo": "...", "mensagem": "..." }`.

---

## How the solution was built

The project follows **Spec-Driven Development (SDD)**: every non-trivial change starts
as a written specification before any code is touched. Each feature lives under
`.specs/features/<name>/` and goes through four adaptive phases:

```
┌──────────┐   ┌──────────┐   ┌─────────┐   ┌─────────┐
│ SPECIFY  │ → │  DESIGN  │ → │  TASKS  │ → │ EXECUTE │
└──────────┘   └──────────┘   └─────────┘   └─────────┘
   required      optional*      optional*     required

* skipped automatically when the scope does not need them
```

- **`spec.md`** — problem statement, user stories with WHEN/THEN acceptance criteria,
  and stable requirement IDs (`SAFETY-NN`, `DEF-PERF-NN`, `OBS-NN`, …) that thread
  through design, tasks, and tests.
- **`design.md`** — only when there are real architectural decisions to lock down
  (dependency matrices, component breakdown, data models, mermaid diagrams).
- **`tasks.md`** — atomic execution steps with explicit `Done when` checklists,
  required test types, and gate commands; each task is one focused commit.
- **`STATE.md`** — captures architectural decisions (ADRs), resolved blockers, and
  lessons learned across the whole roadmap, so the *why* survives across sessions.

Why SDD here:

- The challenge brief carries ten themes; specs let each one trace cleanly to the
  feature that resolves it (the table in [`README-CHALLENGE.md`](README-CHALLENGE.md)
  is generated from this).
- Defects are catalogued separately (`.specs/codebase/CONCERNS.md`, C-1..C-10) and each
  spec states which concerns it closes — so the diff and the documentation always agree.
- Tasks are small enough to map 1-to-1 to commits, so `git log --oneline` reads like a
  changelog instead of a wall of refactor noise.

The evolution itself is a chain of **traceable features** under `.specs/`. Each
feature has a `spec.md` (requirements with stable IDs), optionally a `design.md`
(architecture), and a `tasks.md` (atomic execution steps with test gates). Full roadmap:
[`.specs/project/ROADMAP.md`](.specs/project/ROADMAP.md).

### M1 — Quality foundation (complete)

1. **F-SAFETY-NET** — Real test suite covering every fiscal bracket, freight
   multipliers, regression tests for C-1..C-6, and the HTTP contract. Went from 2
   broken tests to 56 green ones.
2. **F-UPGRADE** — Java 21 + Spring Boot 3.5.14; Spotless + Checkstyle + JaCoCo bound
   to `verify`. Removed the old `JAVA_HOME` workaround.
3. **F-CLEAN** — Refactored to Clean Architecture: `domain` / `application` / `adapter`,
   ports and adapters, DTOs split out, `switch` replacing nested `if/else`.

### M2 — Functional defects (complete)

4. **F-DEFECTS-FUNCTIONAL** — Resolved **C-1** (stateless calculator), **C-2** (invalid
   tax regimes → HTTP 400), **C-3** (missing region → HTTP 400), **C-4** (`BigDecimal`
   HALF_EVEN scale 2 for all money).
5. **F-DEFECTS-PERFORMANCE** — Resolved **C-6**: the four synchronous side-effect calls
   became `IntegrationEvent` publications to Kafka. Producer in the use case, consumers
   under each bounded context, `@RetryableTopic` for retry and DLT, `IdempotencyStore`
   for dedupe. Local stack via `docker compose up` with Confluent KRaft. **64 fast
   tests + 1 slow + EmbeddedKafka end-to-end.**

### M3 — Operations (in flight)

6. **F-RESILIENCE** — Resilience4j `@CircuitBreaker` on each of the four outbound
   adapters with per-port thresholds in `application.properties`. Resolved **C-8**
   (preserves the interrupt flag and rethrows a typed `IntegrationAdapterException`).
   `@TimeLimiter` deferred (would force `CompletableFuture` on every port) — see
   AD-027.
7. **F-OBSERVABILITY** — JSON logs via `logstash-logback-encoder`, Micrometer metrics
   wired to 4 explicit SLIs (API success rate, latency, Kafka dispatch success,
   side-effect end-to-end), OpenTelemetry tracing through `micrometer-tracing-bridge-otel`
   → Jaeger (local) / X-Ray (AWS). Operator reference:
   [`docs/observability.md`](docs/observability.md). Local stack adds Jaeger to the
   compose file (UI on `localhost:16686`).
8. **F-AWS** — Terraform for API Gateway HTTP + ECS Fargate + MSK + CloudWatch +
   X-Ray. Authentication (Cognito/JWT) **documented**, not implemented. **Planned.**

### Notable architectural decisions

Recorded in [`.specs/project/STATE.md`](.specs/project/STATE.md) (28 ADRs total):

- **AD-009** Clean Architecture with adapter-owned JSON DTOs — domain/application are
  free of Spring and Jackson.
- **AD-013/014/015/016** Kafka picked for the challenge (SQS would likely be simpler
  in AWS production for this exact workflow); local consumers live in the same Spring
  Boot codebase for the demo, but the ideal production architecture splits each
  bounded context into its own service.
- **AD-017..AD-022** F-OBSERVABILITY: 4 SLIs frozen; Prometheus + Jaeger locally,
  CloudWatch + X-Ray in AWS; `logstash-logback-encoder` 8.0; cardinality budget
  (`orderId`/`invoiceId`/`correlationId` are never metric tags).
- **AD-023..AD-025** F-DEFECTS-PERFORMANCE: Spring Kafka `@RetryableTopic` instead of
  a custom retry manager; `IdempotencyStore` is in-memory and documented as
  non-durable; Kafka beans are gated on an explicit `app.messaging.kafka.enabled`
  property rather than `@ConditionalOnBean` (which is unreliable against
  auto-configurations).
- **AD-026..AD-028** F-RESILIENCE: Resilience4j Spring Boot 3 starter for circuit
  breakers; `@TimeLimiter` deferred to avoid forcing `CompletableFuture` on every
  port; C-8 fix scoped to introduce `IntegrationAdapterException` and preserve the
  interrupt flag.

### Where to look for the rest

- **[`.specs/project/ROADMAP.md`](.specs/project/ROADMAP.md)** — feature sequence and completion criteria.
- **[`.specs/project/STATE.md`](.specs/project/STATE.md)** — ADRs, resolved blockers, lessons learned.
- **[`.specs/codebase/CONCERNS.md`](.specs/codebase/CONCERNS.md)** — defects C-1..C-10, with evidence and the feature that resolved each.
- **[`.specs/codebase/ARCHITECTURE.md`](.specs/codebase/ARCHITECTURE.md)** — detailed layer diagram.
- **[`.specs/features/<name>/`](.specs/features/)** — spec / design / tasks per feature.
- **[`docs/business-rules.md`](docs/business-rules.md)** — frozen fiscal contract (brackets, freight, side effects).
- **[`docs/translation-changelog.md`](docs/translation-changelog.md)** — audit of the Portuguese → English rename.
- **[`CLAUDE.md`](CLAUDE.md)** — day-to-day operational guide for contributors (commands, layout, constraints).
- **[`README-CHALLENGE.md`](README-CHALLENGE.md)** — original challenge brief plus the README → feature mapping.
