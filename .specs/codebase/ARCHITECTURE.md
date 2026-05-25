# Architecture

**Pattern:** single-module Spring Boot API organized as Clean Architecture / Ports and Adapters.
The domain and application layers contain the business model and use-case orchestration. The
adapter layer contains the technical mechanisms that connect the application to HTTP, Kafka,
security, observability, resilience, bean wiring, and simulated external integrations.

## High-Level Structure

```text
HTTP request
   |
   v
adapter/security
   |-- adapter/security/ratelimit/RateLimitFilter   (per-IP, before BearerTokenAuthenticationFilter)
   |-- JWT validation (HS256)
   |-- invoice:write authorization
   v
adapter/web/InvoiceController
   |
   | maps JSON DTOs <-> domain models
   v
adapter/observability/UseCaseObservation
   |
   v
application/GenerateInvoiceUseCase
application/GenerateInvoiceInteractor
   |
   +-- domain/service/TaxRateTable
   +-- domain/port/TaxRateCalculator
   +-- domain/port/FreightCalculator
   +-- domain/port/InvoiceSideEffectDispatcher
        ^
        |
adapter/messaging/KafkaInvoiceSideEffectDispatcher
   |
   | publishes 4 Kafka events and waits for broker ack
   v
Kafka topics
   |
   +-- adapter/integration/stock/StockDeductionConsumer -> domain/port/StockPort
   +-- adapter/integration/registration/InvoiceRegistrationConsumer -> domain/port/InvoiceRegistrationPort
   +-- adapter/integration/delivery/DeliverySchedulingConsumer -> domain/port/DeliveryPort
   +-- adapter/integration/finance/AccountsReceivableConsumer -> domain/port/AccountsReceivablePort
```

Every outbound integration adapter method is wrapped with `@CircuitBreaker(name=<port>)` and
`@Bulkhead(name=<port>)` (Resilience4j, SEMAPHORE variant). Calibration lives in
`application.properties` under `resilience4j.circuitbreaker.instances.<name>.*` and
`resilience4j.bulkhead.instances.<name>.*`. Rejections bubble up to the consumer's
`@RetryableTopic` handling.

`adapter/config/ApplicationBeanConfig` wires the domain services and application interactor.
`adapter/messaging/KafkaMessagingConfig` (+ `KafkaTopicsConfig`) wires the Kafka publisher,
dispatcher, topics, idempotency store, listener factory, and consumers when
`app.messaging.kafka.enabled=true`.

## Dependency Direction

The intended dependency direction is inward:

```text
adapter -> application -> domain
```

The domain declares ports: `FreightCalculator`, `TaxRateCalculator`,
`InvoiceSideEffectDispatcher`, `StockPort`, `InvoiceRegistrationPort`, `DeliveryPort`,
`AccountsReceivablePort`. Adapter classes implement or wrap these ports using concrete
technologies.

The domain and application layers must not depend on Spring MVC, Jackson, Kafka, Micrometer,
Spring Security, Resilience4j, OpenAPI/springdoc, or transport DTOs.

## Layer Rules

- `domain/` contains business models, exceptions, ports, and business-rule services.
- `domain/` has no Spring, Jackson, Kafka, Micrometer, Resilience4j, or security imports.
- `application/` contains use-case contracts and interactors.
- `application/` orchestrates domain services and ports, but does not own transport,
  persistence, messaging, observability, security, or resilience concerns.
- `adapter/` owns framework and infrastructure concerns: HTTP controllers, JSON DTOs, exception
  envelopes, Spring bean wiring, Kafka publishing/consuming, external integration simulations,
  security, rate limiting, observability, resilience policies, and OpenAPI configuration.
- `InvoiceGeneratorApplication` is only the Spring Boot entry point.

## Why `adapter/` Is Large

In this architecture, `adapter/` is not only "external integrations". It is the boundary layer for
anything that adapts the core application to the outside world or to framework infrastructure.

Current adapter responsibilities:

- `adapter/web`: `InvoiceController`, `ApiExceptionHandler`, JSON DTOs under `dto/`, and
  `OpenAPIConfig` (springdoc — exposes `/v3/api-docs`, `/v3/api-docs.yaml`, `/swagger-ui.html`
  with the `bearer-jwt` security scheme).
- `adapter/security`: `SecurityConfig` (filter chain), `ApiSecurityProperties`, login flow under
  `login/` (`AuthController`, `JwtIssuer`, `InMemoryUserStore`, demo users, request/response
  DTOs, typed login exceptions), 401/403 envelopes under `error/`
  (`ApiBearerAuthenticationEntryPoint`, `ApiBearerAccessDeniedHandler`), and per-IP rate
  limiting under `ratelimit/` (`RateLimitFilter`, `RateLimitPolicy`, `RateLimitConfig`,
  `ClientIpResolver`, `RateLimitErrorWriter`, `RateLimiterMeterFilter`).
- `adapter/observability`: `CorrelationIdFilter`, `UseCaseObservation`, `InvoiceMetricsRecorder`,
  Kafka MDC/header plumbing (`InvoiceKafkaHeaders`, `KafkaHeaderEnricher`,
  `MdcRestoringRecordInterceptor`, `SideEffectTimingConsumerListener`),
  `RejectionCode`, `ObservabilityConfig`, and `ResilienceEventLogger` (F-DEBUG-LOGS —
  subscribes to `CircuitBreakerRegistry` + `BulkheadRegistry` event publishers and emits
  WARN/INFO log lines on state transitions and bulkhead rejections). Backs the four SLIs
  frozen in `.specs/features/observability/spec.md` (HTTP success rate, HTTP latency,
  Kafka dispatch success, side-effect end-to-end latency) and the debug-log catalog
  frozen in `.specs/features/debug-logs/spec.md`.
- `adapter/messaging`: `IntegrationEvent` envelope, `InvoiceTopics` constants,
  `IntegrationEventPublisher` (+ `IntegrationEventPublishException`),
  `KafkaInvoiceSideEffectDispatcher`, `IdempotencyStore`, `KafkaTopicsConfig`,
  `KafkaMessagingConfig`.
- `adapter/integration`: outbound port implementations for stock, fiscal registration, delivery
  scheduling, and accounts receivable, plus Kafka consumers that call those ports. Every adapter
  method carries `@CircuitBreaker` + `@Bulkhead`. Wrapped failures throw
  `IntegrationAdapterException` (typed `RuntimeException`) — interrupts are re-flagged via
  `Thread.currentThread().interrupt()` before throwing. Delivery additionally has
  `DeliverySchedulingClient` (the simulated slow client).
- `adapter/config`: `ApplicationBeanConfig` — explicit Spring bean wiring for the application
  interactor and domain services.

This keeps `domain/` and `application/` small, stable, and easy to unit test.

## Current HTTP Data Flow

1. Client authenticates via `POST /api/auth/login` and receives a JWT.
2. Client `POST`s Portuguese snake_case JSON to `/api/orders/generate-invoice`.
   The legacy alias `/api/pedido/gerarNotaFiscal` is also supported and shares the same
   rate-limit bucket.
3. `RateLimitFilter` (per-IP, before `BearerTokenAuthenticationFilter`) checks the
   `invoice-generate` / `auth-login` / `default` group; over-limit returns HTTP 429 with
   `{codigo: RATE_LIMIT_EXCEEDED, ...}` + `Retry-After` header. `/actuator/**`, `/v3/api-docs/**`,
   `/swagger-ui/**` are exempt.
4. `adapter/security` validates the JWT and requires `SCOPE_invoice:write` for invoice generation.
5. `adapter/web` deserializes JSON into DTOs and maps DTOs to the domain `Order`.
6. `adapter/observability/UseCaseObservation` creates the `invoice.generate` observation around
   the use-case call.
7. `GenerateInvoiceInteractor.generateInvoice(order)` selects the tax rate via `TaxRateTable`.
8. If tax-regime/person-type input is invalid, the domain throws
   `InvalidInvoiceOrderException`; `ApiExceptionHandler` returns HTTP 400 with the standard error
   envelope.
9. The interactor delegates per-item tax math to `TaxRateCalculator`.
10. It computes freight via `FreightCalculator`; missing/null delivery region is rejected with the
    same typed domain exception.
11. Calculated money is rounded via `Money.rounded` (`BigDecimal`, scale 2, `HALF_EVEN`).
12. The interactor builds the domain `Invoice`.
13. It calls `InvoiceSideEffectDispatcher.dispatch(invoice)`.
14. The Kafka dispatcher publishes four integration events and waits for broker acknowledgement.
15. The controller maps the domain `Invoice` back to response DTOs and returns HTTP 200.

HTTP 200 means "invoice calculated and Kafka dispatch accepted". It does not mean the stock,
registration, delivery, or finance side effects have already completed.

## Current Async Side-Effect Flow

`KafkaInvoiceSideEffectDispatcher` publishes one event per downstream side effect:

- `invoice.stock-deduction.v1`
- `invoice.registration.v1`
- `invoice.delivery-scheduling.v1`
- `invoice.accounts-receivable.v1`

Each topic has a consumer in `adapter/integration/**`:

- `StockDeductionConsumer` -> `StockIntegrationAdapter` -> `StockPort`.
- `InvoiceRegistrationConsumer` -> `InvoiceRegistrationAdapter` -> `InvoiceRegistrationPort`.
- `DeliverySchedulingConsumer` -> `DeliveryIntegrationAdapter` -> `DeliveryPort` (uses
  `DeliverySchedulingClient`).
- `AccountsReceivableConsumer` -> `AccountsReceivableAdapter` -> `AccountsReceivablePort`.

Consumers use `IdempotencyStore` (in-memory, not durable — see AD-024) to skip already processed
event IDs, acknowledge duplicates, and avoid repeating side effects after retry/redelivery. Retry
topics and DLT behavior are configured with Spring Kafka `@RetryableTopic` (attempts=4,
exponential backoff via `app.kafka.retry.*`).

Each adapter call runs under `@CircuitBreaker` + `@Bulkhead`. Bulkhead calibration is
asymmetric: `deliveryPort.max-concurrent-calls=5` (tight, because the simulated delivery client
sleeps 5s), `stock=registration=accountsReceivable=20`. `max-wait-duration=0` everywhere —
fail-fast; rejected calls bubble to the retry topic.

The delivery integration still contains the slow-path behavior for invoices with more than five
items, but that latency is now absorbed by the Kafka consumer path instead of the HTTP request
thread.

## Current Functional Policy

- C-1 is fixed: `LegacyProductTaxRateCalculator` is stateless per call.
- C-2 is fixed: JURIDICA + `OUTROS`/null rejects with HTTP 400.
- C-3 is fixed: missing/null delivery region rejects with HTTP 400.
- C-4 is fixed: monetary fields use `BigDecimal`, with scale 2 `HALF_EVEN` rounding for calculated
  money.
- C-6 is mitigated on the HTTP path: delivery's +5s trap still exists in the delivery integration,
  but it runs asynchronously after Kafka consumption.
- C-7 is closed (2026-05-23): the sample payload directory is now `payloads/` (was `paylods/`);
  every doc, test, and spec reference has been updated.
- C-8 is closed: `IntegrationAdapterException` (typed `RuntimeException`) replaces every raw
  `RuntimeException` wrap around `InterruptedException` in `adapter/integration/**`; the interrupt
  flag is preserved before throwing.
- Dispatch semantics: successful invoice generation requires the broker to acknowledge all four
  event publications (changed from legacy synchronous port calls).

## Code Organization

```text
src/main/java/br/com/itau/invoicegenerator/
|-- InvoiceGeneratorApplication.java
|-- adapter/
|   |-- config/                 ApplicationBeanConfig
|   |-- integration/            IntegrationAdapterException
|   |   |-- delivery/           DeliveryIntegrationAdapter, DeliverySchedulingClient, DeliverySchedulingConsumer
|   |   |-- finance/            AccountsReceivableAdapter, AccountsReceivableConsumer
|   |   |-- registration/       InvoiceRegistrationAdapter, InvoiceRegistrationConsumer
|   |   `-- stock/              StockIntegrationAdapter, StockDeductionConsumer
|   |-- messaging/              KafkaInvoiceSideEffectDispatcher, IntegrationEvent(Publisher|PublishException),
|   |                           IdempotencyStore, InvoiceTopics, KafkaMessagingConfig, KafkaTopicsConfig
|   |-- observability/          UseCaseObservation, CorrelationIdFilter, InvoiceMetricsRecorder,
|   |                           InvoiceKafkaHeaders, KafkaHeaderEnricher, MdcRestoringRecordInterceptor,
|   |                           SideEffectTimingConsumerListener, RejectionCode, ObservabilityConfig,
|   |                           ResilienceEventLogger (F-DEBUG-LOGS)
|   |-- security/               SecurityConfig, ApiSecurityProperties
|   |   |-- error/              ApiBearerAuthenticationEntryPoint, ApiBearerAccessDeniedHandler
|   |   |-- login/              AuthController, JwtIssuer, InMemoryUserStore, DemoUser,
|   |   |                       LoginRequest, LoginResponse, Invalid{Credentials,LoginPayload}Exception
|   |   `-- ratelimit/          RateLimitFilter, RateLimitPolicy, RateLimitConfig, ClientIpResolver,
|   |                           RateLimitErrorWriter, RateLimiterMeterFilter
|   `-- web/                    InvoiceController, ApiExceptionHandler, OpenAPIConfig
|       `-- dto/                request/response DTOs (snake_case Portuguese JSON contract)
|-- application/                GenerateInvoiceUseCase, GenerateInvoiceInteractor
`-- domain/
    |-- exception/              InvalidInvoiceOrderException
    |-- model/                  Order, Invoice, Money, ...
    |-- port/                   FreightCalculator, TaxRateCalculator, InvoiceSideEffectDispatcher,
    |                           StockPort, InvoiceRegistrationPort, DeliveryPort, AccountsReceivablePort
    `-- service/                TaxRateTable, ...
```

## Companion Artifacts (outside `src/main/java`)

- `infra/terraform/` — proposal-grade Terraform for the F-AWS deployment write-up. Gate is
  `terraform fmt -recursive -check && terraform init -backend=false && terraform validate`
  (validates clean; not applied to a real account). Architecture write-up:
  `docs/aws-architecture.md`.
- `docs/business-rules.md` — frozen contract for tax brackets, freight, side effects, and known
  defects.
- `docs/observability.md` — operator-facing SLI catalog, Prometheus queries, runbook.
- `docs/bulkhead-strategy.md` — calibration table and AD-033 rationale for bulkhead sizing.
- `docs/auth-strategy.md` — F-AUTH rationale (intentional divergence from edge-validates
  production recommendation).
