# State

**Last Updated:** 2026-05-25
**Current Work:** M7 planning started — F-CLOUDWATCH-METRICS spec + design + tasks drafted under `.specs/features/cloudwatch-metrics/` (REQ-1..14, T1..T6 vertical-slice, AD-CWM-1..4 *proposed* in design.md only; no code, no AD ratified — ADs land at implementation time per workflow). Library target: `io.micrometer:micrometer-registry-cloudwatch2` activated via `@Profile("aws")`. All thirteen prior roadmap features remain complete (M5 closed F-RATELIMIT + F-BULKHEAD + F-API-DOCS — F-SAFETY-NET, F-UPGRADE, F-CLEAN, F-DEFECTS-FUNCTIONAL, F-DEFECTS-PERFORMANCE, F-RESILIENCE, F-OBSERVABILITY, F-AWS, F-DEPLOY-ACTION, F-AUTH, F-RATELIMIT, F-BULKHEAD, F-API-DOCS). 137 fast tests passing; Newman 14 requests / 27 assertions / 0 failures including the new F-RATELIMIT 429 proof. `docs/observability.md` is the operator SSOT for observability, `docs/bulkhead-strategy.md` is the operator SSOT for bulkhead calibration, `docs/aws-architecture.md` is the reviewer-facing AWS proposal. F-RATELIMIT adds per-IP rate limiting via `resilience4j-ratelimiter` (auth-login 5/min, invoice-generate 30/min, default 60/min; actuator exempt) wired via `OncePerRequestFilter` in the existing `SecurityFilterChain`; F-BULKHEAD adds semaphore bulkheads on the four outbound adapters (delivery=5, others=20, fail-fast); F-API-DOCS exposes Swagger UI at `/swagger-ui.html` documenting the JWT bearer flow.

---

## Recent Decisions (Last 60 days)

### AD-001: English-language rename of all Java identifiers (2026-05-22)

**Decision:** Translate Java packages, classes, methods, fields, comments to English. Keep JSON keys snake_case Portuguese and enum *values* Portuguese.
**Reason:** User preference for English code; cleaner long-term; aligns the codebase with how the rest of the team will likely read it. README's no-payload-change rule keeps the contract intact via `@JsonProperty`.
**Trade-off:** Mixed-language enums (`PersonType.FISICA` looks odd at first glance). Documented in `docs/business-rules.md` §7 glossary.
**Impact:** Every downstream feature can refer to English names. Translation audit captured in `docs/translation-changelog.md`.

### AD-002: Preserve the legacy behavior bit-for-bit during the rename (2026-05-22)

**Decision:** No business logic changes were made during the Portuguese→English rename, including the known defects (static list, OUTROS fallthrough, freight=0, double money).
**Reason:** Rename and bug fix are different categories of change; mixing them poisons the diff.
**Trade-off:** Tests still flaky after the rename (the static-list bug is preserved exactly as before).
**Impact:** Defects are addressed under M2 (F-DEFECTS-*), not earlier.

### AD-003: Sequencing — safety net → upgrade → Clean Arch → defects → ops (2026-05-22)

**Decision:** Build real tests first, then upgrade Java/Spring, then refactor to Clean Architecture, then fix defects within the new structure, then ops.
**Reason:** Refactoring without tests is dangerous; upgrading after refactoring forces double work. User confirmed this ordering.
**Trade-off:** Visible improvements (defect closure, observability) come later. The challenge reviewer may not see the "wow" features until M3.
**Impact:** M1 is internal-quality work; output is invisible to API consumers.

### AD-004: All 10 README themes in scope for v1 (2026-05-22)

**Decision:** Treat every theme from the README's "O que considerar na solução" as an in-scope feature, grouped into M1/M2/M3 milestones.
**Reason:** The challenge brief is the contract; partial coverage looks like a partial solution.
**Trade-off:** Larger scope than a "minimum delivery" approach.
**Impact:** ROADMAP.md has 8 features across 3 milestones.

### AD-006: Use Maven profile for slow-tag execution (2026-05-22; updated 2026-05-23)

**Decision:** Surefire 2.22.2 (inherited from the old Spring Boot 2.6.2 baseline) could not reliably filter by JUnit 5 `@Tag` through CLI overrides. Expose slow tests via a `<profile id="slow">` rather than CLI properties. After F-UPGRADE, the explicit Surefire version pin was removed and the Spring Boot 3.5.14 parent manages Surefire 3.5.5.
**Reason:** Spent ~6 attempts trying `-Dgroups=slow` + `-DexcludedGroups=` combinations against surefire 2.22.2; none worked. 3.2.5 + profile is the simplest stable path. Empty `<excludedGroups></excludedGroups>` in the profile silently fails to override the base — using a sentinel value like `<excludedGroups>none</excludedGroups>` is required.
**Trade-off:** None currently; plugin management is back under the Spring Boot parent.
**Impact:** Slow tests run via `./mvnw test -Pslow`. Documented in `CLAUDE.md` and the F-SAFETY-NET tasks.

### AD-007: Spring Boot 3.5.14 + Java 21 for F-UPGRADE (2026-05-23)

**Decision:** Upgrade directly from Spring Boot 2.6.2 / Java 11 to Spring Boot 3.5.14 / Java 21.
**Reason:** 3.5.14 is the newest Spring Boot 3.x parent available in Maven Central at implementation time; the challenge asks for Boot 3.x, not Boot 4.x.
**Trade-off:** This takes the latest 3.x line rather than the newer 4.x line, preserving the feature scope and avoiding an unnecessary major-version jump.
**Impact:** `./mvnw verify` runs on the default JDK 21 shell without the old `JAVA_HOME` workaround. Lombok is now managed at a JDK-21-compatible version.

### AD-008: Spotless + Checkstyle as the F-UPGRADE style gate (2026-05-23)

**Decision:** Add Spotless with google-java-format and a Checkstyle import policy. Bind both to `verify`.
**Reason:** Formatting should be mechanical, and the first enforceable rule should be low-noise: no wildcard, redundant, or unused imports.
**Trade-off:** The initial Spotless application touched most Java files. This is format-only churn, isolated inside F-UPGRADE.
**Impact:** `./mvnw verify` is now the CI-style command: tests, formatting check, Checkstyle, and JaCoCo report.

### AD-009: Clean Architecture layering with adapter-owned JSON DTOs (2026-05-23)

**Decision:** Split the code into `domain`, `application`, and `adapter` packages. Keep domain/application free of Spring and Jackson by moving JSON `@JsonProperty` annotations to DTOs under `adapter/web/dto`.
**Reason:** The next defect-fix phase needs stable use-case and port boundaries before changing behavior.
**Trade-off:** The web adapter now maps between DTOs and domain models explicitly, adding boilerplate.
**Impact:** HTTP payloads stay unchanged while business logic becomes testable without Spring or transport concerns.

### AD-010: Durable async over fire-and-forget threads (2026-05-23)

**Decision:** Do not treat detached threads or untracked `CompletableFuture.runAsync` as the production solution for outbound side effects. Current code stays synchronous until F-DEFECTS-PERFORMANCE/F-RESILIENCE introduces Kafka-backed durable async processing.
**Reason:** Fire-and-forget can hide errors, lose work on process shutdown, provide no retry, and weaken traceability.
**Trade-off:** Request latency remains high until Kafka producer/consumer dispatch is implemented.
**Impact:** ROADMAP points performance/resilience work toward Kafka producers/consumers with retry/DLQ, with `CompletableFuture` only acceptable as a documented local experiment.

### AD-013: Kafka for simulated external service calls (2026-05-23)

**Decision:** Every integration adapter/client call currently represented by `Thread.sleep(...)` is treated as a simulated asynchronous external service call. The four side-effect calls after invoice generation must be dispatched through Kafka: stock deduction, invoice registration, delivery scheduling, and accounts receivable.
**Reason:** These downstream services may be unavailable or slow. Kafka gives a durable async boundary so the HTTP request can return while consumers retry until the service is available or route exhausted failures to DLQ.
**Trade-off:** The invoice response can no longer mean all downstream side effects have completed; completion becomes eventually consistent and must be observable.
**Impact:** F-DEFECTS-PERFORMANCE now has a dedicated Kafka spec/tasks folder. F-RESILIENCE and F-AWS should use Kafka/MSK retry/DLQ language instead of generic queue/SQS wording unless the user revisits this decision.

### AD-014: Kafka chosen, SQS noted as simpler AWS alternative (2026-05-23)

**Decision:** Keep Kafka as the chosen implementation for F-DEFECTS-PERFORMANCE because the user explicitly wants Kafka and a local Docker Compose stack with Kafka.
**Reason:** The roadmap is also a technical demonstration; Kafka makes topics, partitions, consumer groups, retry topics, DLT, and consumer idempotency visible.
**Trade-off:** For this exact production workload, SQS would likely be simpler in AWS. The four integrations are command-style side effects with one logical consumer each, so they mostly need decoupling, retry, and DLQ rather than Kafka stream replay, long-lived event logs, or many subscriber groups.
**Impact:** Specs must implement Kafka now, but F-AWS can mention SQS as a lower-operational-complexity alternative if the architecture proposal compares options.

### AD-015: Local consumers in repo, production consumers in downstream services (2026-05-23)

**Decision:** For the technical test, keep Kafka publisher and consumers in this same Spring Boot codebase. For ideal production architecture, invoice-generator publishes only; stock, fiscal registration, delivery, and accounts-receivable services own their consumers.
**Reason:** The single-repo implementation makes the Kafka flow demonstrable with Docker Compose. The production boundary keeps downstream business behavior with the teams/services that own those domains.
**Trade-off:** The local implementation is intentionally less decomposed than the production architecture.
**Impact:** Specs and docs must call same-codebase consumers a local/demo compromise, not the target production ownership model.

### AD-016: HTTP invoice response means generated plus dispatched (2026-05-23)

**Decision:** `POST /api/orders/generate-invoice` returns the generated invoice after domain calculation and successful Kafka publication of downstream integration events.
**Reason:** The user expects the endpoint to generate the invoice. Making downstream systems async should not make invoice calculation itself async.
**Trade-off:** HTTP success does not mean stock deduction, fiscal registration, delivery scheduling, or accounts-receivable posting has completed. Those become eventually consistent operations.
**Impact:** Current JSON response shape remains unchanged for the challenge, but future API evolution should consider explicit processing status or a status endpoint.

### AD-011: Invalid fiscal/freight inputs reject with typed HTTP 400 (2026-05-23)

**Decision:** For F-DEFECTS-FUNCTIONAL, reject unsupported or missing juridica tax regimes and missing/null delivery region with `InvalidInvoiceOrderException`, mapped by the web adapter to HTTP 400 and JSON fields `codigo` and `mensagem`.
**Reason:** The previous behavior hid data quality problems by returning `items=[]` or `freightValue=0`, which matched the README's reported value inconsistencies.
**Trade-off:** Some malformed requests that previously returned 200 now fail fast. This is intentional because those 200 responses were wrong invoices.
**Impact:** C-2 and C-3 are resolved. Future validation can add Bean Validation, but the domain policy is already explicit.

### AD-012: BigDecimal money with HALF_EVEN scale 2 (2026-05-23)

**Decision:** Use `BigDecimal` for domain and DTO monetary fields. Calculated money is rounded with scale 2 and `RoundingMode.HALF_EVEN`.
**Reason:** Primitive `double` arithmetic is unsuitable for invoice money and was tracked as C-4.
**Trade-off:** Values like `72 × 1.048` now return `75.46` instead of `75.456`. The API still serializes numbers, but calculated values are now BRL-scale.
**Impact:** C-4 is resolved. Tests compare `BigDecimal` values semantically.

### AD-017: SLI catalog frozen — four service-level indicators (2026-05-23)

**Decision:** F-OBSERVABILITY commits to exactly four SLIs: SLI-1 API success rate, SLI-2 API latency (99 % < 800 ms via histogram bucket fraction), SLI-3 Kafka dispatch success, SLI-4 side-effect end-to-end latency (95 % < 30s producer→consumer-ack).
**Reason:** Without a frozen list, metric proliferation becomes inevitable. Picking up-front which ratios matter forces every counter/timer to justify its existence by feeding an SLI or by debugging one.
**Trade-off:** Some operationally interesting signals (e.g., per-bracket calculator latency) are intentionally not promoted to SLIs. They remain queryable metrics but do not get dashboards/alarms.
**Impact:** The spec at `.specs/features/observability/spec.md` is structured around these four; F-AWS dashboards and alarms must reuse the same definitions verbatim.

### AD-018: Local Prometheus+Jaeger / AWS CloudWatch+X-Ray observability split (2026-05-23)

**Decision:** Micrometer is the single instrumentation abstraction. Local Docker Compose uses the Prometheus registry exposed at `/actuator/prometheus` (scraped by a Prometheus container) and OpenTelemetry OTLP → Jaeger for traces. AWS uses the Micrometer CloudWatch registry (or ADOT collector → CloudWatch metrics) and ADOT → X-Ray for traces. Profile boundary (`application-local.yml` vs `application-aws.yml`) controls the registry/exporter dependency.
**Reason:** Mirrors the deployment-boundary split already chosen for F-DEFECTS-PERFORMANCE (AD-015). Demonstrating a real observability stack locally with Docker Compose is the whole point of the technical-test version; CloudWatch is the natural AWS production target.
**Trade-off:** Two profile configurations to maintain, and the local Prometheus stack adds containers. The application code is unchanged between profiles.
**Impact:** F-OBSERVABILITY ships Prometheus + Jaeger as part of the local compose stack. F-AWS adds the CloudWatch registry/dashboards/alarms.

### AD-019: `logstash-logback-encoder` for structured JSON logs (2026-05-23)

**Decision:** Use `net.logstash.logback:logstash-logback-encoder` as the Logback JSON encoder. Logs go to stdout as single-line JSON. MDC carries `correlationId`, `traceId`, `spanId`, `invoiceId`, `orderId`.
**Reason:** De facto standard JSON encoder for Logback; predictable schema, well-tested integration with Logback appenders, no Spring-Boot-specific lock-in. Stdout JSON is consumable by both the local Docker log driver and CloudWatch FireLens in production.
**Trade-off:** Adds a dependency; encoder configuration lives in `logback-spring.xml`. Multi-line stack traces are serialized into a single JSON field, which is the desired behavior for log aggregation.
**Impact:** OBS-01..OBS-07 implementation depends on this dependency and the corresponding `logback-spring.xml`.

### AD-020: Cardinality budget — `orderId` / `invoiceId` / `correlationId` are never metric tags (2026-05-23)

**Decision:** Free-text or high-cardinality identifiers (order/invoice/correlation/trace/span IDs, customer identifiers) are restricted to **logs and trace attributes only**, never used as Micrometer metric tags. The F-OBSERVABILITY spec carries a per-tag cardinality table that bounds every approved tag.
**Reason:** Unbounded label cardinality is the standard way to make Prometheus and CloudWatch unhealthy. Every metric tag must have a finite, enumerable set of values.
**Trade-off:** Some debugging questions ("how slow was order X?") cannot be answered from metrics alone — they require a trace or log lookup. This is the correct design.
**Impact:** Reviewable in PRs; an automated guard (unit test that introspects registered meters' tag values, or a Checkstyle rule on `Meter.Builder.tags(...)` calls) is part of F-OBSERVABILITY's tasks.

### AD-021: Micrometer Tracing + OTel bridge for Spring Boot 3.5 (2026-05-23)

**Decision:** F-OBSERVABILITY uses `io.micrometer:micrometer-tracing-bridge-otel` + `io.opentelemetry:opentelemetry-exporter-otlp` for tracing. The new `spring-boot-starter-opentelemetry` is **not** adopted because it only exists in Spring Boot 4.0+ (announced on the Spring blog on 2025-11-18) and the project is on Spring Boot 3.5.14 (AD-007). Property prefix is `management.otlp.tracing.*`.
**Reason:** Pinning the correct artifact set up-front prevents the spec from drifting into a 4.x-only configuration that wouldn't compile on the current parent.
**Trade-off:** When the project later upgrades to Boot 4.x, this dependency block becomes one line of starter-replacement work. Acceptable.
**Impact:** F-OBSERVABILITY design.md dependency matrix is locked. F-AWS reuses the OTLP exporter against an ADOT sidecar.

### AD-022: `logstash-logback-encoder:8.0` (Logback 1.5 line) (2026-05-23)

**Decision:** Pin `net.logstash.logback:logstash-logback-encoder:8.0` explicitly (not in the Spring Boot BOM). Logback 1.5 is what Spring Boot 3.5 ships, and the 8.x line is the one compatible with Logback 1.5.
**Reason:** The encoder is the seam for JSON logs + MDC enrichment used by every other observability piece (OBS-01..OBS-07). Mismatched Logback / encoder versions cause `NoSuchMethodError` at startup.
**Trade-off:** One more explicit `<version>` to maintain in `pom.xml`. The alternative — letting Maven choose the latest 8.x — is fine but explicit is reviewable.
**Impact:** `pom.xml` gets a direct dependency entry under F-OBSERVABILITY execution.

### AD-023: Spring Kafka `@RetryableTopic` for retry/DLT topology (2026-05-23)

**Decision:** Use Spring Kafka's `@RetryableTopic` annotation on each consumer method for retry and dead-letter routing, with `attempts=4` and exponential backoff (`delay=60000ms`, `multiplier=5.0`) → effective delays 1m → 5m → 25m → DLT. Backoff is property-driven (`app.kafka.retry.delay-ms`, `app.kafka.retry.multiplier`) so tests can override to sub-second values.
**Reason:** Spring Kafka 3.x provides the routing, topic auto-creation, and partition wiring out of the box. Writing a custom retry/DLT manager would re-derive what the framework already does. The 25m third retry is one bucket short of the spec's 30m target but stays inside the same operational tier.
**Trade-off:** Retry topic names follow Spring Kafka's convention (`-retry-{N}`, `-dlt`) rather than the spec's conceptual `.retry.1m/.5m/.30m` suffixes. The semantics are equivalent; renaming via `topicSuffixingStrategy` and `RetryTopicSuffixes` is straightforward if a stakeholder wants exact spec topic names.
**Impact:** `IdempotencyStore` protects against the at-least-once redelivery this introduces. F-RESILIENCE may add per-adapter circuit breakers on top, but the retry path itself is owned here.

### AD-024: In-memory `IdempotencyStore` keyed on `(topic, eventId)` (2026-05-23)

**Decision:** Consumers dedupe Kafka events via a process-local `ConcurrentHashMap` of `(topic, eventId)` pairs maintained by `IdempotencyStore`. Mark-after-success: the entry is recorded only after the downstream port returns, so a transient failure followed by retry is still re-attempted.
**Reason:** Acceptable for the technical-test demo. A real production deployment must replace this with a durable store (Redis, Postgres) because a process restart wipes the dedupe set. The class javadoc and `INTEGRATIONS.md` both flag the non-durability.
**Trade-off:** Restart-on-redelivery duplicates a side effect. Cluster scale-out duplicates across nodes. Both are acceptable in the demo because the downstream stubs are no-op `Thread.sleep`.
**Impact:** Tracked as a deferred idea: "Swap IdempotencyStore for a durable backend before any production rollout." F-AWS Terraform should reserve a Redis/ElastiCache footprint if the production path is built.

### AD-025: Kafka beans gated by explicit `app.messaging.kafka.enabled` property (2026-05-23)

**Decision:** `KafkaMessagingConfig` is `@ConditionalOnProperty(name="app.messaging.kafka.enabled", havingValue="true")`. The property defaults to `true` in `application.properties`; tests that want to skip the Kafka beans set it to `false` and provide a `@Primary` no-op `InvoiceSideEffectDispatcher` (see `NoOpKafkaTestConfig`).
**Reason:** `@ConditionalOnBean(KafkaTemplate.class)` proved unreliable on user `@Configuration` classes — the condition is evaluated before `KafkaAutoConfiguration` contributes the template bean, so the condition spuriously returns false even when Kafka is enabled. An explicit property avoids the auto-config-ordering trap.
**Trade-off:** One more piece of configuration to remember when writing a Spring-context test. Mitigated by the shared `NoOpKafkaTestConfig` plus the same `app.messaging.kafka.enabled=false` line on `@TestPropertySource`.
**Impact:** Future Kafka-related code should be added under `KafkaMessagingConfig` so it inherits the same gate. F-OBSERVABILITY's Kafka-side bindings will use the same condition.

### AD-026: Resilience4j Spring Boot 3 starter for circuit breaking (2026-05-23)

**Decision:** Use `io.github.resilience4j:resilience4j-spring-boot3` + `resilience4j-micrometer` (2.2.0) for circuit breakers on the four outbound port adapters. Each adapter method is annotated with `@CircuitBreaker(name="<port>")`; the per-port configuration lives in `application.properties` under `resilience4j.circuitbreaker.instances.<name>.*`.
**Reason:** Resilience4j is the de-facto Spring Boot 3.x circuit-breaker library; the Spring Cloud Circuit Breaker abstraction is itself wrapped around Resilience4j here. Annotation-based wiring keeps the adapters readable and the per-port config externalised so SREs can tune thresholds without code changes. Micrometer bindings come for free.
**Trade-off:** Adds a third-party dependency outside the Spring Boot BOM (Resilience4j manages its own versioning). The 2.2.x line is compatible with Spring Boot 3.5.
**Impact:** F-OBSERVABILITY will scrape `resilience4j.circuitbreaker.state{name,state}` and `resilience4j.circuitbreaker.calls{name,kind}` meters automatically once the Prometheus endpoint lands.

### AD-027: Defer `@TimeLimiter` per-call timeout to a follow-up (2026-05-23)

**Decision:** F-RESILIENCE T1 ships with `@CircuitBreaker` only. `@TimeLimiter` is intentionally not adopted because it forces a `CompletableFuture<T>` return type on every adapter method, which would propagate to the domain ports and the consumers without measurable upside for this challenge.
**Reason:** The downstream "slowness" is a `Thread.sleep` simulation bounded at 5 s (delivery + >5-item trap). The Kafka consumer thread pool can absorb that latency for the demo. A real production deployment with HTTP downstream calls must add `@TimeLimiter` (or its programmatic equivalent) — that work is captured in `Deferred Ideas` and belongs in either a follow-up to F-RESILIENCE or in F-AWS during the move to MSK.
**Trade-off:** No per-call timeout today. A pathological adapter (e.g., a real downstream hung indefinitely) would still hold the consumer thread until the broker session times out (typically minutes).
**Impact:** Documented in `INTEGRATIONS.md`/`CONCERNS.md` so it does not get forgotten. Migration to async return types is a one-feature scope when the time comes.

### AD-028: C-8 interrupt-flag fix scope (2026-05-23)

**Decision:** While F-RESILIENCE T1 touches every adapter, replace the legacy `catch (InterruptedException e) { throw new RuntimeException(e); }` pattern with `Thread.currentThread().interrupt();` followed by `throw new IntegrationAdapterException(...)`. The typed exception is a new class in the adapter layer.
**Reason:** The legacy pattern dropped the interrupt flag, which prevents executors and scheduler pools from shutting down cleanly. Now that the adapter calls run on Kafka consumer threads (which are pooled and need to react to interrupts), the legacy behaviour was an SRE hazard. The typed exception lets Resilience4j and `@RetryableTopic` see a stable exception class while keeping a recognisable type for logs/metrics.
**Trade-off:** Existing callers must accept `IntegrationAdapterException` as well as plain `RuntimeException`. Since the only callers today are the Kafka consumers (which catch any throwable for retry/DLT), the blast radius is zero.
**Impact:** C-8 is closed. Future adapters added to `adapter/integration/**` must follow the same pattern; a small code-style note in `CONVENTIONS.md` would be a worthwhile follow-up.

### AD-030: F-AWS scope — proposal-grade Terraform, MSK provisioned, Fargate only (2026-05-23)

**Decision:** F-AWS ships **proposal-grade** Terraform (validates clean with
`terraform fmt + init + validate`; not applied against a real AWS account). The
messaging plane is **Amazon MSK** (3 × `kafka.t3.small`, SASL/IAM, KMS at-rest) and
the compute plane is **ECS Fargate** only — no Lambda alternative. All three choices
were captured interactively with the user before T1; ADR-029 / ADR-030 / ADR-033 in
`docs/aws-architecture.md` document them in the reviewer-facing artifact.

**Reason:** Applyable Terraform requires an AWS account + ~US$ 200/mo MSK spend +
state-backend wiring; out of scope for a challenge project. MSK was kept (instead of
pivoting to SQS per AD-014) because fidelity with the local Kafka topology — same
4 topics + retry + DLT shape, no code change — outweighs the cost saving for this
proposal. Fargate over Lambda because the app holds long-lived Kafka consumer
threads; Lambda would split the HTTP path from the consumers and break the
end-to-end trace.

**Trade-off:** No live smoke test; the runbook in `docs/aws-architecture.md`
documents the steps from validate-clean to first `terraform apply`. The SQS
alternative is captured as Future Considerations for an SRE team that decides MSK is
operationally too heavy.

**Impact:** Five-module tree under `infra/terraform/` (network, msk, ecs,
api-gateway, observability). `terraform fmt -recursive -check + init -backend=false
+ validate` is the new F-AWS gate, analogous to `./mvnw verify` for the Java side.
The four F-OBSERVABILITY SLIs from `docs/observability.md` are re-expressed as
CloudWatch metric math verbatim in the `observability` module — SSOT preserved.
M3 closes with F-AWS.

### AD-032: F-AUTH scope — demo-grade in-app HS256 JWT, in-memory user store, scope-based authZ, JwtTestSupport (2026-05-24)

**Decision:** F-AUTH ships an **in-app JWT issuer + Spring Security 6 OAuth2 Resource
Server** for demonstration purposes. Four design choices freeze the implementation:

1. **HS256 symmetric signing** with a shared secret from `app.security.jwt.secret`,
   instead of RS256 + JWKS. The same `NimbusJwtEncoder` and `NimbusJwtDecoder` beans
   read the same `SecretKey` bean.
2. **In-memory `InMemoryUserStore`** holding two hardcoded users (`demo`/`demo123`,
   `admin`/`admin123`). Passwords hashed at bean construction via
   `BCryptPasswordEncoder` — the source code carries no plaintext nor literal BCrypt
   hashes.
3. **Scope-based authorization** via `SCOPE_*` authorities. Spring Security's default
   `JwtAuthenticationConverter` maps the space-separated `scope` JWT claim to
   `SCOPE_<value>` `GrantedAuthority` instances; the filter chain calls
   `.hasAuthority("SCOPE_invoice:write")` on the invoice endpoints.
4. **`JwtTestSupport` `@TestComponent`** mints real HS256 tokens via the production
   `JwtEncoder` bean. Integration tests attach `Authorization: Bearer <token>` and
   exercise the full filter chain. No `@WithMockUser`, no
   `@AutoConfigureMockMvc(addFilters=false)`.

**Reason:** The user explicitly chose this in-app implementation as a working demonstration
of the OAuth2 Resource Server pattern in Spring Boot 3.5.x, **diverging from the
edge-validates recommendation in `docs/auth-strategy.md` and ADR-032 of
`docs/aws-architecture.md`**. Both recommendations remain authoritative for production
deployments; this implementation is for the technical challenge only.

The four sub-decisions follow:

- HS256 over RS256: single-app demo needs one property, not a JWKS endpoint.
- In-memory over DB/LDAP: mirrors the AD-024 `IdempotencyStore` precedent — demo
  artifact, production replaces with a durable backend.
- Scope-based over role-based: aligns with OAuth2 conventions and the JWT shape
  documented in `docs/auth-strategy.md`.
- `JwtTestSupport` over Spring Security test shortcuts: the F-OBSERVABILITY audit
  (AD-029) taught the project that "bean wired but not exercised under the real
  filter chain" is the failure mode that ships broken code. Real tokens through the
  full chain is the only way to catch the next instance of that.

**Trade-off:** A real production deployment must do all of:

- Move to RS256 + JWKS at the IdP boundary so the secret no longer needs to be shared.
- Replace `InMemoryUserStore` with a real user directory + password reset / lockout /
  audit.
- Add refresh tokens, rate limiting on `/api/auth/login`, optional method security
  (`@PreAuthorize`).
- Either keep the in-service JWT validation only as defense-in-depth behind a
  validated edge, or remove it entirely and trust gateway-propagated claim headers
  (the path documented in `docs/auth-strategy.md`).

These are all captured in `ROADMAP.md` Future Considerations.

**Impact:** New `adapter/security/` package (12 files) — `SecurityConfig`,
`ApiSecurityProperties`, `login/{AuthController, JwtIssuer, InMemoryUserStore,
DemoUser, LoginRequest, LoginResponse, InvalidCredentialsException,
InvalidLoginPayloadException}`, `error/{ApiBearerAuthenticationEntryPoint,
ApiBearerAccessDeniedHandler}`. `ApiExceptionHandler` extended with two new mappings.
`pom.xml` gains `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`
+ `spring-security-test` (test scope). `application.properties` gains the
`app.security.jwt.*` block. Four existing integration tests gain
`Authorization: Bearer` headers via the new `JwtTestSupport` helper. Two new
integration test classes (`AuthControllerIntegrationTest`, `SecurityIntegrationTest`)
add 15 tests. Total fast test count: 88 → 103.

### AD-031: F-DEPLOY-ACTION scope — proposal-grade GitHub Actions deploy pipeline, OIDC, commented triggers (2026-05-23)

**Decision:** F-DEPLOY-ACTION ships a **proposal-grade** GitHub Actions workflow at
`.github/workflows/deploy-aws.yml` — the YAML parses cleanly as a runnable workflow but
the `on:` triggers are commented out with an inert `workflow_dispatch` placeholder so
nothing fires against a live AWS account. AWS authentication uses GitHub OIDC
federation (`permissions: id-token: write` + `aws-actions/configure-aws-credentials@v4`
with `role-to-assume`); no `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` references
anywhere in the workflow. Deploy strategy is ECS rolling update via a freshly
registered task-definition revision, where the new revision is rendered by mutating
the **live** task definition (`aws ecs describe-task-definition` + `jq` image swap),
not from a hand-rolled JSON template. Image tag is the immutable `${{ github.sha }}`.

**Reason:** Mirrors AD-030 (F-AWS proposal-grade posture) — the deploy pipeline can be
read in five minutes and audited as a runnable artifact, without burning the AWS
account + MSK spend + OIDC trust-policy bootstrap that a live run would need. OIDC is
the production-grade auth pattern in 2026 (no long-lived secret rotation, no
secret-leakage blast radius); committed access keys would have been a worse
demonstration even if they were operationally simpler. Live-task-def-re-render
preserves every Terraform-managed field (env vars, resource sizes, ADOT sidecar)
across deploys — a hand-rolled template would silently drift the moment the F-AWS ECS
module added a new field. Rolling deploy was chosen over blue/green via CodeDeploy
because the two-task Fargate service already configures rolling-deploy thresholds, and
blue/green needs a second Terraform module + a CodeDeploy app/group + a deploy-time
IAM role for no measurable benefit on a challenge project.

**Trade-off:** No live deploy smoke test (same trade-off as AD-030). The runbook in
`docs/aws-architecture.md` documents the steps from validate-clean to first live
run; flipping the workflow to active requires uncommenting four lines of `on:` block,
provisioning the IAM role + OIDC trust policy, populating the four repo variables
and two secrets, and removing the inert `workflow_dispatch` placeholder.

**Impact:** F-DEPLOY-ACTION closes the gap F-AWS explicitly left as out-of-scope ("CI/CD
pipeline ... a separate concern"). Two new root Terraform outputs (`ecs_cluster_name`,
`ecs_service_name`) were added to `infra/terraform/outputs.tf` so the pipeline reads
live ECS resource names rather than re-deriving naming conventions. The F-AWS gate
(`terraform fmt -recursive -check + init -backend=false + validate`) stays green after
the additions. The roadmap closes M3 with F-DEPLOY-ACTION as the ninth and final feature.

### AD-029: F-OBSERVABILITY audit — registered ≠ wired (2026-05-23)

**Decision:** During F-OBSERVABILITY T5 audit, the T3 commit (`8ec1421`) had landed
`InvoiceMetricsRecorder` and `RejectionCode` as beans but never modified
`InvoiceController` or `ApiExceptionHandler` to call them — the commit message claimed the
wiring existed but the diff showed only new files. Same gap had been left in T2 for the
`MdcRestoringRecordInterceptor` (declared as a bean, never attached to a listener container
factory). Both were closed under T4/T5: T4 wired the interceptor via a custom
`kafkaListenerContainerFactory` with `CompositeRecordInterceptor`; T5 wired
`recordGenerated`/`recordRejected` in the controller and handler and added
`MetricsIntegrationTest` (3 tests, MockMvc + `/actuator/prometheus` scrape) to prove the
counters actually increment under real HTTP traffic.
**Reason:** A bean that compiles and unit-tests green can still be silently unused in
production. The cardinality guard test catches forbidden tags, but no test was catching
"recorder never called". `MetricsIntegrationTest` plugs that gap with a scrape-based
end-to-end assertion that's cheap to extend whenever a new business counter lands.
**Trade-off:** Three extra Spring-context-loading tests (~10 s each). Worth it — without
them the recorder is invisible-in-production code that nothing notices.
**Impact:** Pattern to repeat for every future "recorder/interceptor as bean" feature:
register *and* attach *and* prove via a scrape/log/trace assertion. Commit message + diff
must agree, audited at T5 before flipping ROADMAP to COMPLETE.

### AD-005: Terraform as default IaC for the AWS deployment (2026-05-22)

**Decision:** Use Terraform (not CDK) for the IaC artifact under F-AWS.
**Reason:** Industry-default for AWS provisioning; not coupled to the application language; reviewable in plain HCL by anyone (challenge reviewer needn't know Java CDK). User confirmed *documented + IaC* was desired but did not pin Terraform vs CDK; defaulting to Terraform with this ADR as a reversible decision point.
**Trade-off:** CDK lets us colocate infra with the Java app and reuse types; Terraform requires duplicating naming conventions in HCL.
**Impact:** F-AWS plans Terraform modules under `infra/terraform/`. If user prefers CDK before F-AWS starts, revisit this ADR.

### AD-033: F-BULKHEAD scope — semaphore variant, per-adapter not global, calibrated `delivery=5 / others=20`, fail-fast (2026-05-24)

**Decision:** F-BULKHEAD ships **semaphore bulkheads** on the four outbound adapters that already
carry the F-RESILIENCE circuit breaker. Four sub-decisions freeze the implementation:

1. **`SEMAPHORE` variant**, not `THREADPOOL`. The threadpool variant forces every adapter
   to return `CompletableFuture<T>`, which would propagate through ports and the use case —
   exactly the trade-off AD-027 rejected for `@TimeLimiter`. The semaphore variant is a
   counter around the existing synchronous call, with zero signature changes.
2. **One bulkhead per adapter**, not a single global one. A slow adapter (delivery,
   `Thread.sleep(5000)`) must not be allowed to starve permits a fast adapter (stock,
   `Thread.sleep(380)`) could have used. Watertight compartments, like the metaphor name implies.
3. **Calibration option A: `deliveryPort.max-concurrent-calls=5`, the other three at `20`.**
   Delivery is intentionally tighter because its 5-second simulated latency means each in-flight
   call holds its permit for 5 seconds; a high ceiling there would authorise the system to start
   a large number of slow operations in parallel. Rationale frozen in `docs/bulkhead-strategy.md`
   §4-5.
4. **`max-wait-duration=0` everywhere — fail-fast.** A permit-exhausted call is rejected
   immediately with `BulkheadFullException` instead of blocking the consumer thread waiting for
   a refill. Rejected calls bubble to `@RetryableTopic` (the existing retry path); blocking
   would defeat the back-pressure point.

**Reason:** After F-RESILIENCE the four adapters were protected against *failures* (CB reacts
to a 50% failure rate). They had no protection against *concurrency*: a future
`@KafkaListener(concurrency=N)` bump or a runaway producer could fan out unbounded parallel
calls before the circuit breaker even saw the first failure. The bulkhead is the missing
pre-failure guardrail. Combined with F-RATELIMIT (request-rate at the HTTP boundary) and
F-RESILIENCE (failure-rate on outbound), it closes the three independent back-pressure axes.

**Trade-off:** The numbers were not derived from a load test — they are insurance against
foreseeable concurrency bumps, not throughput tuning. Today, the listener concurrency is `1`
per consumer; the bulkhead sits near-empty in steady state. Numbers are tunable in
`application.properties` per environment without code changes. The semaphore-only choice means
no per-call timeout — pathological adapters (real downstream hung forever) would still hold the
consumer thread until the broker session times out. That gap is documented in AD-027 and
unchanged by AD-033.

**Impact:** Four `@Bulkhead(name=...)` annotations added alongside the existing
`@CircuitBreaker`. Four properties blocks added under `resilience4j.bulkhead.instances.*` in
`application.properties`. New `BulkheadEnforcementTest` (2 tests, programmatic Resilience4j —
same pragmatic shape as `CircuitBreakerLifecycleTest`). New operator-facing doc
`docs/bulkhead-strategy.md` with the supermarket-checkout analogy + calibration table +
decision log. `resilience4j-micrometer` auto-publishes the two bulkhead meters on
`/actuator/prometheus` with no extra collector code. F-OBSERVABILITY's cardinality budget
(AD-020) is preserved — `name` is the only tag, bounded to the four instance names.

### AD-034: F-API-DOCS scope — springdoc-openapi 2.8.x, light annotation level, docs surface `permitAll` + rate-limit-exempt, no DTO `@Schema(description)` (2026-05-24)

**Decision:** F-API-DOCS ships **`org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.13`**
(the line compatible with Spring Boot 3.5.14 / Spring 6.2 `ControllerAdviceBean` API; the 2.6.x
line proved incompatible with a `NoSuchMethodError` at runtime). Four sub-decisions freeze the
implementation:

1. **springdoc over Springfox.** Springfox is EOL since 2020; springdoc is the de-facto Spring
   Boot 3 OpenAPI 3 generator in 2026. Single starter brings spec generation + Swagger UI.
2. **Light annotation level.** `OpenAPIConfig` declares the info block + server URL + the
   `bearer-jwt` HTTP security scheme; `@Operation(summary, description)` + `@SecurityRequirement`
   on the three productive endpoints. **No DTO `@Schema(description=...)` annotations** —
   `docs/business-rules.md` is the SSOT for field meaning and per-field docs scattered across
   DTO files rot the moment business rules evolve.
3. **Docs surface `permitAll` + F-RATELIMIT-exempt.** `/v3/api-docs/**`, `/v3/api-docs.yaml`,
   `/swagger-ui/**`, `/swagger-ui.html` are added to `SecurityConfig.permitAll`. F-RATELIMIT
   already does not throttle them (they live outside `/api/**`, falling through `RateLimitPolicy`
   to the implicit-exempt `null` group). Docs behind auth is a non-feature; a reviewer must be
   able to `curl http://localhost:8080/swagger-ui.html` from a fresh checkout.
4. **`bearer-jwt` document-level + `@SecurityRequirements({})` override on the login endpoint.**
   The OpenAPI document declares one security scheme (HTTP, `scheme=bearer`, `bearerFormat=JWT`)
   and applies it at the document level. `AuthController.login` opts out so Swagger UI can call
   it without a token (chicken-and-egg — the endpoint *issues* tokens). The override is the
   documented OpenAPI 3 pattern.

**Reason:** The challenge currently surfaces three endpoints through `README.md` `curl`
snippets, `docs/auth-strategy.md` prose, and the Newman collection. None is machine-readable
or interactive. springdoc adds an OpenAPI 3 JSON document at `/v3/api-docs` (consumable by
Postman import, OpenAPI Generator, AWS API Gateway integrations) and a Swagger UI at
`/swagger-ui.html` (interactive "Try it out"). The diff is minimal — one dependency, one
`@Configuration` bean, three method-level annotations, six `permitAll` patterns — and the
reviewer-experience win is large.

**Trade-off:** A working Swagger UI on `:8080` is a docs surface that production deployments
will want to either disable (`springdoc.swagger-ui.enabled=false`) or move behind an internal
gateway. The current `permitAll` is correct for the demo and explicitly documented as such.
The light annotation level means request/response bodies are inferred from DTO shapes —
operations show the `OrderDto` / `InvoiceDto` field tree but not per-field "this is the recipient's
person type, FISICA or JURIDICA" prose. That is the trade-off against rot; readers who want
field semantics follow the link to `docs/business-rules.md`.

**Impact:** New `OpenAPIConfig` `@Configuration` class under `adapter/web/`. Six new
`permitAll` patterns in `SecurityConfig`. `@Operation` + `@SecurityRequirements` on
`AuthController.login`; `@Operation` + `@SecurityRequirement` on
`InvoiceController.generateInvoice`. `pom.xml` gains
`springdoc-openapi-starter-webmvc-ui:2.8.13`. New `OpenApiDocsIntegrationTest` (4 tests:
`/v3/api-docs` reachable anonymously, declares `bearer-jwt`, surfaces the three productive
paths, Swagger UI reachable). Total fast test count grows from 133 (post-F-RATELIMIT) to 137.
`docs/bulkhead-strategy.md` references the AD-033 / AD-034 pair via the M5 milestone.

### AD-035: F-RATELIMIT scope — `resilience4j-ratelimiter`, per-IP buckets via synthesised instances, `Filter`-in-SecurityFilterChain seam, `{codigo, mensagem}` + `Retry-After` envelope (2026-05-24)

**Decision:** F-RATELIMIT ships **per-IP rate limiting via `io.github.resilience4j:resilience4j-ratelimiter`**
(transitively present through the AD-026 `resilience4j-spring-boot3` starter — no new
Maven coordinate). Five sub-decisions freeze the implementation:

1. **Library = `resilience4j-ratelimiter`.** Reuses the same property namespace
   (`resilience4j.*`) and the same `resilience4j-micrometer` binding the four
   circuit breakers already use; one less third-party dependency to audit.
2. **Coverage = per-endpoint groups + actuator exempt.** Three statically-named
   instances configured in `application.properties`: `auth-login` (5/min,
   brute-force defence on `POST /api/auth/login`), `invoice-generate` (30/min,
   shared by canonical + legacy alias), `default` (60/min, catch-all so any
   future `/api/**` endpoint inherits a limit). `/actuator/**` is **exempt** —
   throttling the Prometheus scrape (every 15s) or k8s liveness probes would
   break F-OBSERVABILITY's scrape contract (AUTH-15) and trigger restart loops.
3. **Key = per-IP with `X-Forwarded-For` fallback.** `ClientIpResolver` prefers
   the leftmost `X-Forwarded-For` hop, falls back to
   `HttpServletRequest.getRemoteAddr()`, and returns the literal `"unknown"`
   sentinel for degenerate inputs (filter never throws). Per-IP isolation is
   achieved by synthesising a per-`(group, ip)` `RateLimiter` via
   `registry.rateLimiter(name, prototype.getRateLimiterConfig())` — Resilience4j
   has no built-in key extractor.
4. **Seam = `OncePerRequestFilter` in `SecurityFilterChain`, `addFilterBefore(...,
   BearerTokenAuthenticationFilter.class)`.** Rejected the `@RateLimiter`
   annotation approach (runs *after* the filter chain — abuse traffic pays the
   BCrypt + JWT validation cost first; URI-to-instance mapping would scatter
   across controllers instead of centralising in `RateLimitPolicy`).
5. **Envelope = `{"codigo":"RATE_LIMIT_EXCEEDED","mensagem":"..."}` + `Retry-After`.**
   `RateLimitErrorWriter` writes the envelope directly because filter-level
   rejections never reach `DispatcherServlet` (so `@RestControllerAdvice` cannot
   intercept them). `Retry-After` is the integer ceiling of the configured
   `limit-refresh-period` — the next *guaranteed* refill window. The literal
   string `"RATE_LIMIT_EXCEEDED"` is used directly (not added to the
   `RejectionCode` enum, which is bound to the invoice-domain `invoice.rejected{reason}`
   counter's cardinality budget — same precedent as F-AUTH's
   `INVALID_CREDENTIALS` string).

**Reason:** The user heard the recommendation `/api/auth/login` (the only path
the original `ROADMAP.md` Future Considerations explicitly mentioned) and chose
to extend coverage to every `/api/**` endpoint with per-endpoint groups. Reusing
Resilience4j keeps the resilience stack consolidated (one library for circuit
breakers + bulkheads + rate limiters); `resilience4j-micrometer` ships
`resilience4j.ratelimiter.*` meters for free.

**Trade-off:** Three implementation realities the spec and `docs/business-rules.md`
flag explicitly:

- **In-process registry.** Per-IP `RateLimiter` instances live in a process-local
  `ConcurrentHashMap` with no TTL eviction. Acceptable for the demo (one ECS
  task); production scaling to N tasks needs a distributed store (Redis /
  ElastiCache) so the effective per-IP rate stays at `N × limit-for-period`. The
  trade is documented under RLIM-OOS-3 + Future Considerations.
- **Cardinality risk.** The synthetic per-IP instance names would publish one
  Micrometer time-series per unique IP via `TaggedRateLimiterMetrics`, directly
  violating AD-020. **`RateLimiterMeterFilter` mitigates this** by denying any
  `resilience4j.ratelimiter.*` meter whose `name` tag is not one of the three
  statically-named instances. `RateLimitMetricsIntegrationTest` proves the guard
  via a scrape-based regex check for `:` in the `name` tag.
- **`Retry-After` is conservative.** Resilience4j fixed-window does not expose
  the exact wait time cleanly; the ceiling of `limit-refresh-period` is honest
  about the next *guaranteed* refill rather than approximating the precise wait.

**Impact:** New `adapter/security/ratelimit/` package (6 files):
`RateLimitConfig`, `RateLimitFilter`, `RateLimitPolicy`, `ClientIpResolver`,
`RateLimitErrorWriter`, `RateLimiterMeterFilter`. `SecurityConfig.securityFilterChain`
gains one line (`addFilterBefore`). `ApiExceptionHandler` gains
`@ExceptionHandler(RequestNotPermitted.class)` as defence-in-depth. `application.properties`
gains the `resilience4j.ratelimiter.instances.{auth-login, invoice-generate, default}.*`
block. `AuthControllerIntegrationTest` raises the test-profile limit via
`@TestPropertySource` so its six login calls don't trip prod's 5/min ceiling.
20 new tests across 4 classes (`ClientIpResolverTest` 8 + `RateLimitPolicyTest` 12 +
`RateLimitIntegrationTest` 6 + `RateLimitMetricsIntegrationTest` 2); total fast
test count `103 -> 131` (M5/RATELIMIT) `-> 137` (M5/BULKHEAD+API-DOCS). Postman
collection gains the `RATE_LIMIT_EXCEEDED on 6th attempt` request; Newman last
run = `14 requests / 27 assertions / 0 failures / 2.1s`.

### AD-036: JaCoCo coverage gate — bundle ≥ 85 % line / ≥ 75 % branch with curated excludes (2026-05-25)

**Decision:** `./mvnw verify` now fails the build below **85 % line** or **75 % branch**
coverage at the **bundle** level. One `<rule>` on `BUNDLE` with two `<limit>`s in
`pom.xml`'s `jacoco-maven-plugin > configuration > rules`; a `<execution id="check">`
binds `jacoco:check` to the `verify` phase. Low-signal classes are excluded from
JaCoCo measurement entirely (see `<excludes>` in `pom.xml`):

- Bootstrap: `**/InvoiceGeneratorApplication*`.
- Spring wiring / properties: `**/*Config*`, `**/*Properties*`.
- Adapter contracts: `**/*Dto*`, `**/*Request*`, `**/*Response*`, `**/*Exception*`.
- Use-case interfaces (no executable code): `**/*UseCase*`.
- Domain contracts and data carriers: `**/domain/port/**`, `**/domain/model/**`.
- Static envelopes / constants: `**/adapter/messaging/IntegrationEvent*`,
  `**/adapter/messaging/InvoiceTopics*`,
  `**/adapter/observability/InvoiceKafkaHeaders*`,
  `**/adapter/observability/RejectionCode*`.
- Demo-user data carriers: `**/adapter/security/login/DemoUser*`.

**Reason:** F-SAFETY-NET and F-UPGRADE deferred enforcement with a per-layer
sketch (≥ 80 % domain/use-case, ≥ 60 % adapter). Per-layer JaCoCo rules are
brittle — `<element>PACKAGE</element>` needs careful per-package wiring, and a
wrong glob silently exempts a layer. A **single bundle rule** is simpler to
reason about, review, and tune; the exclude list does the layer-shaping work
explicitly. The 85 % / 75 % values match what the current safety net actually
achieves with a few points of headroom so a single new test cannot flip the
build red. The divergence from the originally-deferred per-layer plan is
intentional. Sequencing: quick task `006` shipped the ReportGenerator HTML view
first so contributors can read the report; quick task `007` then turned the
gate on.

**Trade-off:** Three honest limitations:

- **Bundle-level signal is coarse.** A regression that drops adapter coverage
  while domain coverage rises can hide inside the same bundle ratio. Mitigated
  by the curated exclude list: every excluded class is either a contract,
  bootstrap, constant, or pure data carrier; the remaining covered surface is
  the behavioral code. Tightening later (per-package rules, MUTATION counter,
  etc.) is a forward-compatible change.
- **Glob-based excludes are name-based.** A new `FooConfig` or `BarResponse`
  is implicitly excluded the moment it is created, even if it grows behavior.
  Acceptable for the demo (configs are typically `@Bean` wiring; responses are
  records with `@JsonProperty` only); a future class that breaks the
  convention would need a carve-out or rename.
- **Threshold floor, not target.** 85 % / 75 % is the minimum the gate accepts,
  not the goal. Contributors should still aim higher; the gate prevents the
  most obvious regressions.

**Impact:** `./mvnw verify` is now the authoritative quality gate including
coverage; CI failures on threshold violations replace human review of the HTML
report as the first line of defence. `target/site/jacoco/index.html` remains
the canonical machine-readable report; quick task `006`'s ReportGenerator HTML
at `target/site/coverage/index.html` is the human-readable view. Docs synced
under quick task `007-coverage-threshold-gate`:
`.specs/codebase/{TESTING,STACK,CONCERNS}.md`,
`.specs/project/{STATE,ROADMAP}.md`, and the F-UPGRADE + F-SAFETY-NET
out-of-scope rows.

---

## Active Blockers

None.

## Resolved Blockers

### B-001: Lombok 1.18.22 incompatible with JDK 16+ — resolved 2026-05-23

**Discovered:** 2026-05-22
**Impact before fix:** Builds failed on the active shell JDK (21) and only succeeded under JDK 11 with a `JAVA_HOME` override.
**Resolution:** F-UPGRADE moved the project to Java 21 + Spring Boot 3.5.14. `./mvnw verify` passes on the default JDK 21 shell.

---

## Lessons Learned

### L-001: The `payloads/` typo is load-bearing for the path-based `curl` examples in our docs

**Context:** Documenting the `curl` example for the sample request in CLAUDE.md and translation-changelog.md.
**Problem:** Catching the misspelling mid-rename and "fixing" it would have invalidated every doc reference and broken the manual-test path until they were all updated.
**Solution:** Left as-is, captured in `CONCERNS.md` C-7 as a deferred cosmetic fix. Sweep with F-UPGRADE.
**Prevents:** Mixing cosmetic cleanup into a focused-scope refactor.

### L-003: Stream.findFirst() rejects null first-element — C-3 has TWO broken paths, not one (2026-05-22)

**Context:** Writing characterization tests for SAFETY-19 (delivery address with `region=null`) during F-SAFETY-NET execution.
**Problem:** Spec said the buggy path produces `freight=0` (same as SAFETY-18). Actual behavior: `Stream<Region>::findFirst()` throws `NullPointerException` on a null element — the request fails with HTTP 500, not a silent zero.
**Solution:** Updated `MissingRegionFreightCharacterizationTest` to `assertThrows(NullPointerException.class, ...)`, marked SPEC_DEVIATION in code, updated `business-rules.md` §6.3 and `CONCERNS.md` C-3 to document both broken paths separately.
**Update 2026-05-23:** F-CLEAN T11 removed the accidental NPE path by mapping `Address::getRegion` after finding the matching address. F-DEFECTS-FUNCTIONAL then closed the policy gap by rejecting missing/null delivery region with HTTP 400 and code `INVALID_DELIVERY_REGION`.
**Prevents:** Assuming code "silently returns wrong value" without actually running it. When characterizing a defect, run the code first.

### L-002: A rename that "looks behavior-preserving" can still surface latent test ordering bugs

**Context:** After the English rename, `./mvnw test` failed on a test that *had* passed in isolation.
**Problem:** Initially looked like the rename broke something; it actually exposed the pre-existing static-list bug (C-1) — both legacy and renamed code accumulate in the same way; the test was always order-dependent.
**Solution:** Verified by running the test in isolation (passes) and re-reading the original `CalculadoraAliquotaProduto` (identical static pattern). Captured as the defining example of C-1.
**Prevents:** Mistaking pre-existing bugs for rename regressions; always re-verify the pre-rename behavior before claiming regression.

### L-004: TESTING.md is the SSOT for both Maven + Newman + Docker workflows (2026-05-24)

**Context:** After F-AUTH T6 the Newman pipeline + the `KAFKA_BOOTSTRAP_SERVERS=localhost:29092` env-var override + the `docker compose up -d kafka` recipe lived only in the AI's working memory and the relevant commit messages. A new contributor running through the test docs would not have found them.
**Problem:** A green `./mvnw verify` is no longer the whole story — F-POSTMAN ships a Newman collection; F-AUTH locks the protected endpoints behind a JWT that the auto-login Pre-request script issues; the happy-path requests need a reachable Kafka broker. Three failure modes (401 on first request, 500 on happy-path only, ECONNREFUSED) each have a specific cause that is invisible from `./mvnw test`.
**Solution:** Rewrite `.specs/codebase/TESTING.md` (commit `aab0379`, 2026-05-24) as the single source of truth: class-by-class table of all 103 tests with feature attribution, Newman recipes for both full-compose and local-app + compose-Kafka, the 2026-05-24 verified-run table (8 requests / 24 assertions / 0 failures / 993 ms), auto-login Pre-request script behaviour, failure-mode diagnostics. Everything else (READMEs, business-rules) links to TESTING.md rather than duplicating the recipe.
**Prevents:** Recipe drift across READMEs / CLAUDE.md / specs. Newman-specific failure modes silently turning into "the app is broken" tickets. Future Kafka-bootstrap overrides being rediscovered every time someone tries to run the Postman collection locally.

---

## Quick Tasks Completed

| #   | Description                                            | Date       | Commit  | Status  |
| --- | ------------------------------------------------------ | ---------- | ------- | ------- |
| 001 | Initial CLAUDE.md (init skill)                         | 2026-05-22 | (HEAD)  | ✅ Done |
| 002 | English rename of all Java identifiers                 | 2026-05-22 | (HEAD)  | ✅ Done |
| 003 | docs/business-rules.md (frozen contract)               | 2026-05-22 | (HEAD)  | ✅ Done |
| 004 | docs/translation-changelog.md (rename audit)           | 2026-05-22 | (HEAD)  | ✅ Done |
| 005 | Brownfield mapping (7 docs)                            | 2026-05-22 | (HEAD)  | ✅ Done |
| 006 | PROJECT.md + ROADMAP.md + STATE.md initialized         | 2026-05-22 | (HEAD)  | ✅ Done |
| 007 | F-SAFETY-NET spec.md + tasks.md                        | 2026-05-22 | (HEAD)  | ✅ Done |
| 008 | F-SAFETY-NET Phase 1 — calculator → bean, ctor inject, JaCoCo + surefire profile, test builders | 2026-05-22 | (HEAD) | ✅ Done |
| 009 | F-SAFETY-NET Phase 2 — 9 service test classes (brackets, freight, 4 characterizations)            | 2026-05-22 | (HEAD) | ✅ Done |
| 010 | F-SAFETY-NET Phase 3 — MockMvc integration + JaCoCo report verified                                | 2026-05-22 | (HEAD) | ✅ Done |
| 011 | F-UPGRADE — Java 21, Spring Boot 3.5.14, Spotless, Checkstyle, docs/spec updates                    | 2026-05-23 | (HEAD) | ✅ Done |
| 012 | F-CLEAN — Clean Architecture layers, ports/adapters, DTO mapping, docs/spec updates                 | 2026-05-23 | (HEAD) | ✅ Done |
| 013 | F-CLEAN follow-up — switch-based freight calculation, null-safe region lookup, async guidance       | 2026-05-23 | (HEAD) | ✅ Done |
| 014 | F-DEFECTS-FUNCTIONAL — resolve C-1 through C-4 with stateless tax, 400 validation, BigDecimal money | 2026-05-23 | (HEAD) | ✅ Done |
| 015 | F-OBSERVABILITY spec frozen — SLI catalog, log/metric/trace scope, cardinality budget, AD-017..AD-020 | 2026-05-23 | (HEAD) | ✅ Done |
| 016 | F-OBSERVABILITY design frozen — dependency matrix, components, profile configs, AD-021..AD-022 | 2026-05-23 | (HEAD) | ✅ Done |
| 017 | F-OBSERVABILITY tasks.md frozen — 5 consolidated vertical-slice tasks (per task-granularity feedback) | 2026-05-23 | (HEAD) | ✅ Done |
| 018 | F-DEFECTS-PERFORMANCE T1 — Kafka producer + IntegrationEvent contract + InvoiceSideEffectDispatcher port | 2026-05-23 | (HEAD) | ✅ Done |
| 019 | F-DEFECTS-PERFORMANCE T2 — 4 @KafkaListener consumers + EmbeddedKafka end-to-end integration test | 2026-05-23 | (HEAD) | ✅ Done |
| 020 | F-DEFECTS-PERFORMANCE T3 — @RetryableTopic with 4-attempt backoff + DLT + in-memory IdempotencyStore | 2026-05-23 | (HEAD) | ✅ Done |
| 021 | F-DEFECTS-PERFORMANCE T4 — multi-stage Dockerfile + docker-compose with cp-kafka 7.7 KRaft | 2026-05-23 | (HEAD) | ✅ Done |
| 022 | F-DEFECTS-PERFORMANCE T5 — docs cross-link, ROADMAP/STATE/CONCERNS update, final verify | 2026-05-23 | (HEAD) | ✅ Done |
| 023 | F-RESILIENCE T1 — Resilience4j circuit breakers on 4 outbound adapters; C-8 interrupt-flag fix; CircuitBreakerLifecycleTest | 2026-05-23 | (HEAD) | ✅ Done |
| 024 | F-RESILIENCE T2 — docs closure (ROADMAP/STATE/CONCERNS/CLAUDE) | 2026-05-23 | (HEAD) | ✅ Done |
| 025 | F-AUTH T1 — Spring Security + HS256 JwtEncoder/JwtDecoder beans + permissive chain | 2026-05-24 | `7b3819b` | ✅ Done |
| 026 | F-AUTH T2 — POST /api/auth/login + JwtIssuer + InMemoryUserStore (2 BCrypt users) + error mappings | 2026-05-24 | `3dce9ec` | ✅ Done |
| 027 | F-AUTH T3+T4 — lock down invoice endpoints + JwtTestSupport on 4 existing integration tests | 2026-05-24 | `4f087d5` | ✅ Done |
| 028 | F-AUTH T5 — AuthControllerIntegrationTest (6) + SecurityIntegrationTest (9) | 2026-05-24 | `175fc30` | ✅ Done |
| 029 | F-AUTH T6 — ROADMAP M4 + STATE AD-032 + Postman auto-login + README/CHALLENGE/CLAUDE/business-rules/auth-strategy | 2026-05-24 | `a0c4b95` | ✅ Done |
| 030 | F-AUTH validation — Newman 24/24 assertions green against docker-compose Kafka + local app (993 ms) | 2026-05-24 | (verified, no commit) | ✅ Done |
| 031 | TESTING.md rewrite — class-by-class 103-test table + Newman recipes + docker compose commands + failure-mode diagnostics + L-004 | 2026-05-24 | `aab0379` | ✅ Done |
| 032 | F-RATELIMIT T1 — scaffold ratelimit package (Config + Policy + IP resolver + ErrorWriter) + properties block + ClientIpResolverTest (8) + RateLimitPolicyTest (12) | 2026-05-24 | `1f28999` | ✅ Done |
| 033 | F-RATELIMIT T2 — RateLimitFilter wired into SecurityFilterChain (addFilterBefore) + 6 real-chain integration tests + AuthControllerIntegrationTest test-profile override | 2026-05-24 | `466f0bf` | ✅ Done |
| 034 | F-RATELIMIT T3 + parallel F-BULKHEAD + F-API-DOCS — meter cardinality guard (`RateLimiterMeterFilter`) + `RequestNotPermitted` exception advice + `RateLimitMetricsIntegrationTest` (2); bundled with the parallel F-BULKHEAD + F-API-DOCS work | 2026-05-24 | `13be332` | ✅ Done |
| 035 | F-RATELIMIT T4 — Postman `RATE_LIMIT_EXCEEDED on 6th attempt` request with priming pre-request script; Newman regression 14 requests / 27 assertions / 0 failures / 2.1s against docker compose Kafka + local app | 2026-05-24 | `413e415` | ✅ Done |
| 036 | F-RATELIMIT T5 — docs closure: ROADMAP M5 consolidated (F-RATELIMIT + F-BULKHEAD + F-API-DOCS) + STATE AD-035 + docs/business-rules.md Rate limiting section + docs/observability.md Rate-limit signals + CLAUDE.md / README.md / README-CHALLENGE.md / TESTING.md updates + spec traceability flipped to Verified | 2026-05-24 | (pending) | ✅ Done |

> Commits are pending — none of the above is in git yet beyond the initial commit `0780ce3`. To be staged when the user asks.

---

## Deferred Ideas

Ideas captured during work that belong in future features or phases. Prevents scope creep while preserving good ideas.

- [ ] Add an `Idempotency-Key` header on `POST /api/orders/generate-invoice` so retries are safe — **Captured during:** brownfield mapping. Belongs in F-RESILIENCE.
- [ ] Webhook receiver for the delivery system's async confirmation — **Captured during:** integrations doc. Belongs in F-RESILIENCE.
- [x] Rename `src/main/resources/paylods/` → `payloads/` (C-7) — **Closed:** 2026-05-23. Sweep covered all 24 referencing files (CLAUDE/README/docs/specs/CI workflow); `./mvnw test` green.
- [x] JaCoCo coverage gate in CI with per-layer thresholds (≥80 % domain/use-case, ≥60 % adapter) — **Closed:** 2026-05-25 by quick task `007-coverage-threshold-gate` (commit `70ce7ee`). Final shape diverged from the deferred plan: a uniform **bundle** rule (≥ 85 % line, ≥ 75 % branch) with a curated exclude list instead of per-layer thresholds. See AD-036.
- [ ] Add `@Validated` + Bean Validation annotations on the `Order` payload (e.g., `@Positive`, `@NotNull`) — **Captured during:** brownfield mapping. Belongs in a future validation hardening task; F-DEFECTS-FUNCTIONAL already covers C-2/C-3 domain policy.
- [ ] Document an ADR comparing CDK vs Terraform if the user wants to revisit AD-005 — **Captured during:** AWS sizing question.

---

## Todos

In-progress thoughts and action items that don't fit in active tasks.

- [x] F-DEFECTS-FUNCTIONAL policy chosen and implemented: reject `JURIDICA + OUTROS/null` and missing/null delivery region with typed HTTP 400 responses.
- [ ] Decide F-AWS compute target (ECS Fargate vs Lambda) before writing Terraform modules. Default lean: ECS Fargate (predictable behavior, no cold starts, fits the synchronous critical path of `RegistrationService`).
- [ ] Add `.gitignore` entries for `.specs/` if the user wants the spec-driven artifacts kept local (currently included; recommend keeping them committed for review).
- [ ] During F-DEFECTS-PERFORMANCE, implement Kafka async dispatch for `stockPort`, `invoiceRegistrationPort`, `deliveryPort`, and `accountsReceivablePort` and add retry/DLQ/idempotency coverage.
- [ ] After F-OBSERVABILITY tasks land, create `docs/observability.md` (SLI catalog + Prometheus queries + runbook) and link it from `CLAUDE.md` / `README.md`.

---

## Preferences

**Model Guidance Shown:** never
