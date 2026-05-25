# Tech Stack

**Analyzed:** 2026-05-25

## Core

- Framework: Spring Boot 3.5.14
- Language: Java 21
- Runtime: JVM (HotSpot)
- Package manager: Maven (`./mvnw` wrapper committed)

## Backend

- API Style: REST (Spring `@RestController`)
- Endpoints:
  - `POST /api/auth/login` — JWT issuance (HS256, in-memory user store)
  - `POST /api/orders/generate-invoice` — invoice generation (requires `SCOPE_invoice:write`)
  - `POST /api/pedido/gerarNotaFiscal` — legacy alias (same handler, shares rate-limit bucket)
  - `GET /v3/api-docs`, `GET /v3/api-docs.yaml`, `GET /swagger-ui.html` — OpenAPI surface
  - `GET /actuator/health`, `/actuator/info`, `/actuator/prometheus` — Spring Boot Actuator
- Database: **none** — there is no persistence layer. Downstream business adapters are in-process
  stubs that `Thread.sleep` to simulate latency; `IdempotencyStore` is in-memory (not durable,
  AD-024).
- Authentication: HS256 JWT issued by the app itself (`JwtIssuer` + `InMemoryUserStore` with demo
  users `demo`/`demo123` and `admin`/`admin123`). Validated by Spring Security resource-server
  filter. Intentionally diverges from the edge-validates production recommendation — see
  `docs/auth-strategy.md` and AD-032.
- Messaging: **real** Kafka dependency (cp-kafka in KRaft mode locally). Four command topics +
  retry topics + DLT topic per integration; see `INTEGRATIONS.md`.

## Dependencies (`pom.xml`)

**Runtime:**

- `spring-boot-starter-web` — REST + embedded Tomcat
- `spring-boot-starter` — core
- `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server` — JWT validation
- `spring-kafka` — producer/consumer + `@RetryableTopic` retry/DLT support
- `io.github.resilience4j:resilience4j-spring-boot3` (2.2.0) — `@CircuitBreaker`, `@Bulkhead`,
  `@RateLimiter` annotation support
- `io.github.resilience4j:resilience4j-micrometer` (2.2.0) — auto-publishes Resilience4j metrics
  to Micrometer
- `spring-boot-starter-actuator` — health, info, prometheus endpoints
- `io.micrometer:micrometer-registry-prometheus` — Prometheus scrape endpoint
- `io.micrometer:micrometer-tracing-bridge-otel` + `io.opentelemetry:opentelemetry-exporter-otlp`
  — OTLP trace export (to local Jaeger / AWS ADOT)
- `net.logstash.logback:logstash-logback-encoder` (8.0) — JSON structured logs
- `org.springdoc:springdoc-openapi-starter-webmvc-ui` (2.8.13) — OpenAPI 3 spec + Swagger UI
- `org.projectlombok:lombok` — model boilerplate

**Test:**

- `spring-boot-starter-test` (JUnit 5 + Spring Test; **Mockito artifacts excluded** — tests use
  explicit fakes)
- `spring-security-test` — security filter chain tests
- `spring-kafka-test` — `@EmbeddedKafka` end-to-end tests

## Testing

- Unit: JUnit Jupiter
- Integration: `@SpringBootTest` + MockMvc for HTTP/security/rate-limit contracts;
  `@EmbeddedKafka` for the full HTTP → Kafka → consumer flow
- Newman/Postman: regression collection under `docs/postman/` (rate-limit 429 + happy-path)
- E2E (outside JVM): none
- Coverage tool: JaCoCo 0.8.11 (report-only; no threshold enforced)

## External Services

- *Real local dependency:* Kafka (cp-kafka KRaft) via `docker-compose.yml`.
- *Simulated business systems:* four in-process outbound adapters (`StockIntegrationAdapter`,
  `InvoiceRegistrationAdapter`, `DeliveryIntegrationAdapter`, `AccountsReceivableAdapter`) plus
  `DeliverySchedulingClient`. They `Thread.sleep` to simulate latency; the sleeps are
  intentionally preserved (they now run on the Kafka consumer thread, not the HTTP thread).
- *Cloud target (proposal-grade only):* see `docs/aws-architecture.md` + `infra/terraform/`.

## Development Tools

- Build wrapper: `./mvnw` (POSIX) / `mvnw.cmd` (Windows)
- Formatter: Spotless 2.44.5 + google-java-format 1.22.0
- Style checks: Maven Checkstyle 3.5.0 + Checkstyle 10.17.0 (rules in
  `config/checkstyle/checkstyle.xml`)
- Local stack: `docker compose up --build` (Kafka + app; HTTP 8080, Kafka external listener 29092)
- IaC: Terraform under `infra/terraform/` (proposal-grade; `terraform fmt + init -backend=false +
  validate` is the gate, no real account apply)
- IDE metadata: Eclipse + IntelliJ
- Spring Boot Maven plugin configured for Paketo OCI image builds (not exercised in CI)
