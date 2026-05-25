# External Integrations

## Current State

The four downstream business systems (stock, fiscal registration, delivery, accounts receivable)
remain **simulated** — each is an in-process adapter implementing a domain port and calling
`Thread.sleep` to mimic external latency. The sleeps are intentionally preserved; later iterations
should replace them with real HTTP/gRPC clients while keeping the same port contract.

Kafka, however, is a **real** external dependency. F-DEFECTS-PERFORMANCE wired Spring Kafka end
to end: invoice generation publishes one event per integration, and four `@KafkaListener`
consumers call the existing simulated adapters. Each adapter call also runs under Resilience4j
`@CircuitBreaker` and `@Bulkhead` (F-RESILIENCE + F-BULKHEAD).

Project premise: every `Thread.sleep(...)` site in an integration adapter/client represents a
simulated external asynchronous service call. The production-shaped flow publishes a Kafka
message and processes the external call in a consumer with retry/DLT behavior — that flow is now
in place.

Messaging decision: Kafka was selected for the local implementation (matches the technical-test
requirement and demonstrates stream replay + multiple consumer groups). For a lean AWS production
build of the same command-style workflow, SQS would likely be simpler operationally because each
downstream action has one logical worker and mainly needs decoupling, retry, and DLQ rather than
stream replay (see `docs/aws-architecture.md` and AD-018).

Local/demo boundary: this repository contains both publisher and consumers so the technical test
can demonstrate the full flow with `docker compose up --build`.

Production boundary: invoice-generator should only publish events. Separate stock, fiscal
registration, delivery, and accounts-receivable services should own their consumers and downstream
integration rules.

HTTP boundary: `POST /api/orders/generate-invoice` returns after the invoice is calculated and
all four integration events are acknowledged by the broker. A successful 200 means "invoice
generated and downstream processing requested"; it does not mean any side effect has actually
completed.

## Stock Deduction

**Port:** `domain/port/StockPort`
**Adapter:** `adapter/integration/stock/StockIntegrationAdapter` — `@CircuitBreaker(name="stockPort")` + `@Bulkhead(name="stockPort", maxConcurrent=20)`
**Consumer:** `adapter/integration/stock/StockDeductionConsumer`
**Topic:** `invoice.stock-deduction.v1`
**Purpose:** Notify stock that invoice items should be deducted.
**Simulated latency:** 380 ms.
**Failure mode:** `IntegrationAdapterException` (interrupt flag preserved). Circuit-breaker open
or bulkhead-rejected calls bubble to `@RetryableTopic`.

## Invoice Registration

**Port:** `domain/port/InvoiceRegistrationPort`
**Adapter:** `adapter/integration/registration/InvoiceRegistrationAdapter` — `@CircuitBreaker(name="invoiceRegistrationPort")` + `@Bulkhead(name="invoiceRegistrationPort", maxConcurrent=20)`
**Consumer:** `adapter/integration/registration/InvoiceRegistrationConsumer`
**Topic:** `invoice.registration.v1`
**Purpose:** Register the invoice with the fiscal registry.
**Simulated latency:** 500 ms.

## Delivery Scheduling

**Port:** `domain/port/DeliveryPort`
**Adapter:** `adapter/integration/delivery/DeliveryIntegrationAdapter` — `@CircuitBreaker(name="deliveryPort")` + `@Bulkhead(name="deliveryPort", maxConcurrent=5)`
**Client stub:** `adapter/integration/delivery/DeliverySchedulingClient`
**Consumer:** `adapter/integration/delivery/DeliverySchedulingConsumer`
**Topic:** `invoice.delivery-scheduling.v1`
**Purpose:** Schedule physical delivery.
**Simulated latency:** 150 ms adapter + 200 ms client, plus 5000 ms when `invoice.items.size() > 5`.
**Why the tighter bulkhead:** the +5s path holds a permit for 5s; permitting 20 concurrent
deliveries on a heavy-payload spike would consume the consumer thread pool. Calibration table in
`docs/bulkhead-strategy.md` (AD-033).

## Accounts Receivable

**Port:** `domain/port/AccountsReceivablePort`
**Adapter:** `adapter/integration/finance/AccountsReceivableAdapter` — `@CircuitBreaker(name="accountsReceivablePort")` + `@Bulkhead(name="accountsReceivablePort", maxConcurrent=20)`
**Consumer:** `adapter/integration/finance/AccountsReceivableConsumer`
**Topic:** `invoice.accounts-receivable.v1`
**Purpose:** Notify finance/accounts receivable.
**Simulated latency:** 250 ms.

## Kafka Topics

One command topic per integration; 3 partitions each; message key is `invoiceId` (or `orderId`).

| Topic | Partitions | Producer | Consumer | Consumer group |
| --- | ---: | --- | --- | --- |
| `invoice.stock-deduction.v1` | 3 | `KafkaInvoiceSideEffectDispatcher` | `StockDeductionConsumer` | `invoice-generator-stock-deduction` |
| `invoice.registration.v1` | 3 | `KafkaInvoiceSideEffectDispatcher` | `InvoiceRegistrationConsumer` | `invoice-generator-registration` |
| `invoice.delivery-scheduling.v1` | 3 | `KafkaInvoiceSideEffectDispatcher` | `DeliverySchedulingConsumer` | `invoice-generator-delivery-scheduling` |
| `invoice.accounts-receivable.v1` | 3 | `KafkaInvoiceSideEffectDispatcher` | `AccountsReceivableConsumer` | `invoice-generator-accounts-receivable` |

Each main topic has retry/DLT siblings driven by Spring Kafka `@RetryableTopic` (attempts=4,
exponential backoff via `app.kafka.retry.*` properties; auto-created on startup):

- `<topic>.retry.1m`
- `<topic>.retry.5m`
- `<topic>.retry.30m`
- `<topic>.dlt`

## Idempotency

`adapter/messaging/IdempotencyStore` — in-memory `ConcurrentHashMap` keyed on `(topic, eventId)`,
deduplicates Kafka redelivery so consumers do not double-execute side effects. **Not durable** —
a process restart drops the dedupe set (AD-024). Production rollout needs Redis/Postgres or
consumer-idempotent destination semantics.

## Resilience Policy

Configuration lives in `application.properties` under
`resilience4j.circuitbreaker.instances.<name>.*` and
`resilience4j.bulkhead.instances.<name>.*`. Per-port instance names:
`stockPort`, `invoiceRegistrationPort`, `deliveryPort`, `accountsReceivablePort`.

- `max-wait-duration=0` on every bulkhead — fail-fast; rejected calls bubble to the retry topic.
- `SEMAPHORE` variant only — `THREADPOOL` would force `CompletableFuture<T>` on every port (same
  trade-off AD-027 rejected for `@TimeLimiter`).
- `resilience4j-micrometer` auto-publishes
  `resilience4j.bulkhead.available.concurrent.calls{name}` and circuit-breaker state metrics on
  `/actuator/prometheus`.

## Local Docker Stack

`docker-compose.yml` (root) starts:

- `cp-kafka` (KRaft mode — no ZooKeeper; see `docs/kafka-zookeeper-vs-kraft.md`). External
  listener on host port `29092`.
- The app container built from `Dockerfile`, HTTP on host port `8080`.

Topics are auto-created on app startup via `KafkaTopicsConfig` (the four main topics plus their
retry/DLT siblings).

Smoke test:

```bash
docker compose up --build
TOKEN=$(curl -sS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123"}' | jq -r .access_token)
curl -X POST http://localhost:8080/api/orders/generate-invoice \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d @src/main/resources/payloads/teste-pf.json
```

## Auth (real, in-process)

JWT (HS256) issued by `adapter/security/login/JwtIssuer` against demo users in
`InMemoryUserStore`. Validated by Spring Security resource-server. See `docs/auth-strategy.md`
and AD-032 for the rationale (intentionally diverges from the edge-validates production
recommendation so this repo can demonstrate end-to-end auth without provisioning a real IdP).

## Rate Limiting

Resilience4j `RateLimiter` registry, per-IP, per-group: `auth-login` (5/min), `invoice-generate`
(30/min — canonical + legacy alias share), `default` (60/min). Wired via `RateLimitFilter`
(OncePerRequestFilter) placed before `BearerTokenAuthenticationFilter` so abuse traffic is
rejected before JWT validation cost. `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**` are
exempt. 429 returns `{codigo: RATE_LIMIT_EXCEEDED, mensagem: ...}` + `Retry-After: <seconds>`.

## API Documentation

springdoc-openapi 2.8.13. Spec at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`. Documents
the `bearer-jwt` HTTP security scheme so users can paste a token from `/api/auth/login`. See
AD-034 for the field-semantics SSOT trade-off (DTO docstrings are minimal; prose lives in
`docs/business-rules.md`).

## Future External Services (Cloud Target)

| Category | Likely choice in AWS | Captured in feature |
| --- | --- | --- |
| API Gateway | Amazon API Gateway HTTP API or ALB | F-AWS |
| AuthN / AuthZ | Cognito or JWT verifier at gateway | F-AWS |
| Async messaging | Amazon MSK (Kafka) or SQS with DLQ | F-AWS, AD-018 |
| Resilience | Resilience4j on adapters (same as today) | F-RESILIENCE, F-BULKHEAD |
| Logs | CloudWatch Logs (JSON via logstash encoder) | F-OBSERVABILITY |
| Metrics | Micrometer + CloudWatch Metrics registry | F-OBSERVABILITY |
| Tracing | OpenTelemetry → ADOT → AWS X-Ray | F-OBSERVABILITY |
| IaC | Terraform (proposal-grade under `infra/terraform/`) | F-AWS |
