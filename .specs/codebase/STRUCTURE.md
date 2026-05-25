# Project Structure

**Root:** `/Users/matheustadeu/geradornotafiscal`

## Directory Tree

```
.
├── .mvn/                  Maven wrapper config
├── .specs/                Spec-driven artifacts
│   ├── codebase/          Brownfield mapping (this folder)
│   ├── features/          Per-feature spec/design/tasks
│   └── project/           PROJECT.md, ROADMAP.md, STATE.md
├── config/checkstyle/     Checkstyle rules
├── docker-compose.yml     Local stack: cp-kafka (KRaft) + app
├── Dockerfile             App image used by docker-compose
├── docs/                  Human-facing documentation
│   ├── postman/           Postman + Newman regression collection
│   └── *.md               business-rules, observability, auth-strategy, ...
├── infra/terraform/       F-AWS proposal-grade Terraform (fmt + validate, no apply)
├── src/main/java/br/com/itau/invoicegenerator/
│   ├── InvoiceGeneratorApplication.java
│   ├── adapter/
│   │   ├── config/        Spring bean composition (ApplicationBeanConfig)
│   │   ├── integration/   Simulated outbound systems + IntegrationAdapterException
│   │   ├── messaging/     Kafka publisher, dispatcher, idempotency store, topic config
│   │   ├── observability/ Correlation/MDC, metrics, tracing, Kafka header plumbing
│   │   ├── security/      SecurityConfig + login/, error/, ratelimit/
│   │   └── web/           InvoiceController, ApiExceptionHandler, OpenAPIConfig, dto/
│   ├── application/       Use case API + interactor
│   └── domain/
│       ├── exception/     InvalidInvoiceOrderException (typed business validation)
│       ├── model/         Domain models and enums
│       ├── port/          Inversion boundaries (calculators + side-effect ports)
│       └── service/       Domain services / rule tables
├── src/main/resources/
│   ├── application.properties
│   ├── logback-spring.xml Logstash JSON encoder + profile-aware appenders
│   └── payloads/          Sample request payloads (renamed from paylods/, C-7)
├── src/test/java/br/com/itau/invoicegenerator/
│   ├── adapter/
│   │   ├── integration/   Adapter + consumer + circuit-breaker tests
│   │   ├── messaging/     Kafka dispatch / idempotency / topic config tests
│   │   ├── observability/ Metrics + tracing + correlation tests
│   │   ├── security/      Auth + JWT + filter chain + ratelimit/ tests
│   │   └── web/           InvoiceController integration tests
│   ├── service/           Domain rule unit tests
│   │   └── characterization/  Slow / legacy-behavior tests (@Tag("slow"))
│   ├── testsupport/       Builders, fakes (RecordingTaxRateCalculator, TestUseCases)
│   ├── tracing/           Trace propagation tests
│   ├── web/               Web-layer unit tests
│   ├── GenerateInvoiceInteractorTest.java
│   └── InvoiceGeneratorApplicationTests.java
├── CLAUDE.md
├── README.md
├── README-CHALLENGE.md
├── pom.xml
└── mvnw, mvnw.cmd
```

## Module Organization

### domain/

Pure Java domain code (no Spring/Jackson/Kafka/Resilience4j).

- `domain/model` — `Order`, `Invoice`, `Recipient`, `Address`, `Item`, `InvoiceItem`,
  `Document`, `Money`, `TaxRate`, and enums.
- `domain/port` — `TaxRateCalculator`, `FreightCalculator`, `InvoiceSideEffectDispatcher`,
  `StockPort`, `InvoiceRegistrationPort`, `DeliveryPort`, `AccountsReceivablePort`.
- `domain/service` — `TaxRateTable`, `LegacyProductTaxRateCalculator`, `LegacyFreightCalculator`.
- `domain/exception` — `InvalidInvoiceOrderException` with stable error codes.

### application/

Use case boundary. `GenerateInvoiceUseCase` is the application entry point;
`GenerateInvoiceInteractor` orchestrates rules and ports.

### adapter/

Framework and outside-world code.

- `adapter/web` — Spring MVC, JSON DTO mapping, `ApiExceptionHandler`, `OpenAPIConfig`
  (springdoc).
- `adapter/security` — `SecurityConfig`, JWT issuing/validation under `login/`, 401/403 envelopes
  under `error/`, per-IP rate limiting under `ratelimit/`.
- `adapter/observability` — correlation IDs, Micrometer metrics, Observation API wrapping, Kafka
  MDC/header plumbing, side-effect timing listener, and `ResilienceEventLogger` that emits
  WARN/INFO log lines on circuit-breaker state transitions and bulkhead rejections (F-DEBUG-LOGS).
- `adapter/messaging` — `KafkaInvoiceSideEffectDispatcher`, `IntegrationEventPublisher`,
  `IdempotencyStore`, `InvoiceTopics`, `KafkaMessagingConfig`, `KafkaTopicsConfig`.
- `adapter/integration` — outbound port implementations for stock / registration / delivery /
  finance and their Kafka consumers. Each adapter call carries `@CircuitBreaker` + `@Bulkhead`
  (Resilience4j) and throws `IntegrationAdapterException` on failure (with the interrupt flag
  preserved for `InterruptedException`).
- `adapter/config/ApplicationBeanConfig` — wires the application graph.

### testsupport/

Reusable builders/fakes for tests, including `TestUseCases` and `RecordingTaxRateCalculator`, so
unit tests can instantiate the use case without Spring.

## Where Things Live

- HTTP: `adapter/web/InvoiceController.java`
- Login: `adapter/security/login/AuthController.java`
- JSON contract DTOs: `adapter/web/dto/*.java`
- Use case: `application/GenerateInvoiceUseCase.java`, `GenerateInvoiceInteractor.java`
- Tax-rate selection: `domain/service/TaxRateTable.java`
- Per-item tax math: `domain/service/LegacyProductTaxRateCalculator.java`
- Freight: `domain/service/LegacyFreightCalculator.java`
- Side-effect ports: `domain/port/*.java`
- Side-effect dispatch (Kafka): `adapter/messaging/KafkaInvoiceSideEffectDispatcher.java`
- Side-effect consumption: `adapter/integration/{stock,registration,delivery,finance}/*Consumer.java`
- Resilience policy: `application.properties` keys under
  `resilience4j.{circuitbreaker,bulkhead,ratelimiter}.instances.*`
- Rate-limit filter wiring: `adapter/security/SecurityConfig.java` +
  `adapter/security/ratelimit/RateLimitFilter.java`
- Sample payloads: `src/main/resources/payloads/teste-pf.json`, `teste-pj-simples.json`
- Local stack compose: `docker-compose.yml`
- AWS proposal: `docs/aws-architecture.md`, `infra/terraform/`
