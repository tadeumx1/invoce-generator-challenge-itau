# Testing Infrastructure

## Test Frameworks

- **Unit / Integration:** JUnit Jupiter 5 via `spring-boot-starter-test` 3.5.14.
- **Mocking:** no Mockito usage in the current tests; Mockito is excluded from the test starter to avoid unnecessary JVM self-attach behavior.
- **Spring Test:** `@SpringBootTest` + MockMvc for application and HTTP integration tests; `@EmbeddedKafka` for end-to-end Kafka flow tests; `spring-security-test` available for security tests (the F-AUTH suite uses `JwtTestSupport` real-token tests instead — see [`adapter/security/`](../../src/main/java/br/com/itau/invoicegenerator/adapter/security)).
- **Coverage:** JaCoCo 0.8.11; HTML report at `target/site/jacoco/index.html`.
- **Format/style:** Spotless + google-java-format, Checkstyle import policy. Both bound to `verify`.
- **End-to-end HTTP smoke (F-POSTMAN):** Postman v2.1.0 collection runnable via `npx newman run`. See [Newman / Postman](#newman--postman-end-to-end-http-smoke) below.

## Test Organization

**Location:** `src/test/java/br/com/itau/invoicegenerator/`.

**Main groups:**

- `GenerateInvoiceInteractorTest` verifies use-case orchestration with a recording fake.
- `service/TaxRateSelection*Test` covers tax brackets (parameterized tests across all `(personType, taxRegime, totalItemsValue)` combinations).
- `service/FreightMultiplierTest` covers freight multipliers across all 5 regions.
- `service/characterization/*Test` regression tests for fixed C-1/C-2/C-3 behavior plus the remaining slow C-6 characterization.
- `service/LegacyProductTaxRateCalculatorTest` confirms statelessness post-C-1.
- `web/InvoiceControllerIntegrationTest` verifies HTTP JSON contract with the two sample payloads + the three rejection codes.
- `adapter/integration/InvoiceKafkaFlowIntegrationTest` end-to-end Kafka pipeline via `@EmbeddedKafka` (4 topics → 4 consumers → recording ports).
- `adapter/integration/CircuitBreakerLifecycleTest` Resilience4j circuit-breaker state transitions.
- `adapter/messaging/{IdempotencyStore,KafkaInvoiceSideEffectDispatcher}Test` unit-level Kafka producer + dedupe coverage.
- `adapter/observability/*Test` F-OBSERVABILITY coverage (CorrelationIdFilter, MdcRestoringRecordInterceptor, KafkaHeaderEnricher, InvoiceMetricsRecorder, ActuatorPrometheusIntegrationTest, MetricsIntegrationTest, CardinalityGuardTest, UseCaseObservationTest).
- `adapter/web/MetricsIntegrationTest` proves business counters increment under real HTTP traffic.
- `tracing/HttpTracePropagationIntegrationTest` end-to-end trace + MDC propagation through the use case.
- `adapter/security/{AuthControllerIntegrationTest, SecurityIntegrationTest}` F-AUTH coverage (login flow + filter chain).
- `InvoiceGeneratorApplicationTests` Spring context wiring smoke.

## Test Execution

### Maven (default)

```bash
./mvnw test                                      # fast suite, excludes @Tag("slow")
./mvnw test -Pslow                              # slow characterization suite (C-6)
./mvnw test -Dtest=GenerateInvoiceInteractorTest  # single test class
./mvnw verify                                   # tests + Spotless + Checkstyle + JaCoCo (CI gate)
./mvnw spotless:apply                           # reformat sources (google-java-format)
```

**JDK requirement:** Java 21 (no `JAVA_HOME` override needed — F-UPGRADE removed it).

### Local Docker stack (full integration environment)

The fast Maven suite covers the application end-to-end via `@EmbeddedKafka` and MockMvc; no infrastructure required. The Docker stack is for **manual exploration**, **Postman/Newman runs against a live broker**, and **observability tooling** (Prometheus + Jaeger).

```bash
docker compose up -d                # start kafka + jaeger + app in background
docker compose up -d kafka          # start ONLY kafka (e.g., when running the app via ./mvnw spring-boot:run locally)
docker compose ps                   # see container status
docker compose logs -f invoice-generator   # tail app logs
docker compose down                 # stop and remove all containers + the compose network
docker compose down -v              # additionally wipe named volumes (Kafka topic data)
```

When you bring up only Kafka and run the app locally with `./mvnw spring-boot:run`, point the producer at the external listener:

```bash
docker compose up -d kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./mvnw spring-boot:run
```

Ports exposed:

| Service | Host port | Purpose |
| --- | --- | --- |
| `invoice-generator` | 8080 | HTTP API + `/actuator/health` + `/actuator/prometheus` |
| `invoice-kafka` | 29092 | Kafka external listener (host-side tooling like `kafka-console-consumer`, the local Spring Boot app when run via `./mvnw`) |
| `invoice-jaeger` | 16686 | Jaeger UI (OTLP HTTP receiver on internal `:4318`) |

Health-checks built into the compose file: Kafka has a `kafka-broker-api-versions` healthcheck (5s interval). Wait for `docker inspect -f '{{.State.Health.Status}}' invoice-kafka` to return `healthy` before running anything that needs the broker.

## Coverage Targets

**Current:** JaCoCo report generated by `./mvnw verify`.
**Goal:** add enforcement later, with at least 80 % on domain/application and 60 % on adapters. Captured under ROADMAP Future Considerations.

## Test Coverage Matrix

| Code Layer | Required Test Type | Location Pattern | Run Command |
| --- | --- | --- | --- |
| Domain services | Unit, no Spring | `src/test/java/.../service/*Test.java` | `./mvnw test` |
| Application use case | Unit, no Spring | `GenerateInvoiceInteractorTest` | `./mvnw test` |
| HTTP adapter | Spring/MockMvc integration | `web/InvoiceControllerIntegrationTest` | `./mvnw test` |
| Spring wiring | Context integration | `InvoiceGeneratorApplicationTests` | `./mvnw test` |
| Kafka adapter | `@EmbeddedKafka` end-to-end | `adapter/integration/InvoiceKafkaFlowIntegrationTest` | `./mvnw test` |
| Resilience | Circuit-breaker lifecycle | `adapter/integration/CircuitBreakerLifecycleTest` | `./mvnw test` |
| Observability | Filter/interceptor + MockMvc + Prometheus scrape | `adapter/observability/*Test`, `adapter/web/MetricsIntegrationTest`, `tracing/HttpTracePropagationIntegrationTest` | `./mvnw test` |
| Security | MockMvc + real HS256 tokens via `JwtTestSupport` | `adapter/security/{AuthController,Security}IntegrationTest` | `./mvnw test` |
| Slow delivery characterization | Slow integration-style | `service/characterization/SlowDeliveryCharacterizationTest` | `./mvnw test -Pslow` |
| Full HTTP contract (smoke) | Postman + Newman against live app | `docs/postman/invoice-generator.postman_collection.json` | `npx newman run docs/postman/invoice-generator.postman_collection.json` |

## Gate Check Commands

| Gate Level | When to Use | Command |
| --- | --- | --- |
| Quick | Local implementation loop | `./mvnw test` |
| Slow characterization | Changes near delivery/integration latency | `./mvnw test -Pslow` |
| Build | Phase completion / pre-commit | `./mvnw verify` |
| Smoke (live HTTP) | After deploy or material auth/transport changes | `docker compose up -d && npx newman run docs/postman/invoice-generator.postman_collection.json && docker compose down` |

`./mvnw verify` runs tests, Spotless check, Checkstyle, jar packaging, and JaCoCo report generation.

---

## Test Run Report — 2026-05-24

### Maven fast suite — `./mvnw test`

**Result:** 103 tests, 0 failures, 0 errors, 0 skipped. Duration ~35 s on a 4-core laptop.

| Test Class | Tests | Feature |
| --- | ---: | --- |
| `GenerateInvoiceInteractorTest` | 2 | F-SAFETY-NET / F-CLEAN |
| `service.LegacyProductTaxRateCalculatorTest` | 3 | F-DEFECTS-FUNCTIONAL (C-1) |
| `service.TaxRateSelectionFisicaTest` | 10 (parameterized) | F-SAFETY-NET |
| `service.TaxRateSelectionSimplesNacionalTest` | 8 (parameterized) | F-SAFETY-NET |
| `service.TaxRateSelectionLucroRealTest` | 8 (parameterized) | F-SAFETY-NET |
| `service.TaxRateSelectionLucroPresumidoTest` | 8 (parameterized) | F-SAFETY-NET |
| `service.FreightMultiplierTest` | 6 (parameterized) | F-SAFETY-NET |
| `service.characterization.MissingRegionFreightCharacterizationTest` | 2 | F-DEFECTS-FUNCTIONAL (C-3) |
| `service.characterization.StaticListAccumulationCharacterizationTest` | 1 | F-DEFECTS-FUNCTIONAL (C-1) |
| `service.characterization.UnhandledTaxRegimeCharacterizationTest` | 2 | F-DEFECTS-FUNCTIONAL (C-2) |
| `web.InvoiceControllerIntegrationTest` | 5 | F-SAFETY-NET / F-AUTH (tokenized) |
| `adapter.web.MetricsIntegrationTest` | 3 | F-OBSERVABILITY |
| `adapter.observability.ActuatorPrometheusIntegrationTest` | 2 | F-OBSERVABILITY |
| `adapter.observability.CorrelationIdFilterTest` | 3 | F-OBSERVABILITY |
| `adapter.observability.MdcRestoringRecordInterceptorTest` | 4 | F-OBSERVABILITY |
| `adapter.observability.KafkaHeaderEnricherTest` | 3 | F-OBSERVABILITY |
| `adapter.observability.InvoiceMetricsRecorderTest` | 3 | F-OBSERVABILITY |
| `adapter.observability.CardinalityGuardTest` | 1 | F-OBSERVABILITY (AD-020) |
| `adapter.observability.UseCaseObservationTest` | 3 | F-OBSERVABILITY |
| `tracing.HttpTracePropagationIntegrationTest` | 1 | F-OBSERVABILITY |
| `adapter.integration.InvoiceKafkaFlowIntegrationTest` | 1 | F-DEFECTS-PERFORMANCE (@EmbeddedKafka) |
| `adapter.integration.CircuitBreakerLifecycleTest` | 1 | F-RESILIENCE |
| `adapter.messaging.IdempotencyStoreTest` | 4 | F-DEFECTS-PERFORMANCE |
| `adapter.messaging.KafkaInvoiceSideEffectDispatcherTest` | 3 | F-DEFECTS-PERFORMANCE |
| `adapter.security.AuthControllerIntegrationTest` | 6 | F-AUTH (login flow) |
| `adapter.security.SecurityIntegrationTest` | 9 | F-AUTH (filter chain) |
| `InvoiceGeneratorApplicationTests` | 1 | Context smoke |

Class counts above approximate `@Test` + `@ParameterizedTest` matrices; the authoritative total (103) is reported by Surefire.

### Slow profile — `./mvnw test -Pslow`

| Test Class | Tests | Notes |
| --- | ---: | --- |
| `service.characterization.SlowDeliveryCharacterizationTest` | 1 | C-6 5-second delivery latency floor; runs only with the `slow` profile (excluded from the fast suite via the `@Tag("slow")` filter). |

### `./mvnw verify`

Adds Spotless format check, Checkstyle, jar packaging, and JaCoCo HTML report on top of `./mvnw test`. Same 103 tests; total ~45 s.

---

## Newman / Postman end-to-end HTTP smoke

The F-POSTMAN collection at [`docs/postman/invoice-generator.postman_collection.json`](../../docs/postman/invoice-generator.postman_collection.json) exercises the live HTTP API end-to-end. F-AUTH (T6) added an auto-login Pre-request script so every protected request transparently uses `Authorization: Bearer {{accessToken}}`.

### How to run

```bash
# 1) Bring up the full stack (Kafka + Jaeger + app). Kafka must be reachable from
#    the app for the happy-path POSTs to succeed (they publish four IntegrationEvent
#    messages before returning 200).
docker compose up -d

# 2) Wait for the app to become healthy (Spring Boot startup + Kafka producer init).
until curl -fsS -o /dev/null http://localhost:8080/actuator/health; do sleep 2; done

# 3) Run the collection. --color off avoids ANSI codes when piping the output.
npx newman run docs/postman/invoice-generator.postman_collection.json

# 4) Tear down.
docker compose down
```

Alternative — local app + only Kafka in compose:

```bash
docker compose up -d kafka
until [ "$(docker inspect -f '{{.State.Health.Status}}' invoice-kafka)" = "healthy" ]; do sleep 2; done
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./mvnw spring-boot:run &
until curl -fsS -o /dev/null http://localhost:8080/actuator/health; do sleep 2; done

npx newman run docs/postman/invoice-generator.postman_collection.json

kill %1 && docker compose down
```

Customising `baseUrl` for a non-default host/port:

```bash
npx newman run docs/postman/invoice-generator.postman_collection.json \
  --env-var baseUrl=https://invoice.example.com
```

### Last verified run (2026-05-24)

Against `docker compose up -d` + the F-AUTH-enabled app:

```
┌─────────────────────────┬────────────────────┬───────────────────┐
│                         │           executed │            failed │
├─────────────────────────┼────────────────────┼───────────────────┤
│              iterations │                  1 │                 0 │
│                requests │                  8 │                 0 │
│            test-scripts │                  8 │                 0 │
│      prerequest-scripts │                  8 │                 0 │
│              assertions │                 24 │                 0 │
├─────────────────────────┴────────────────────┴───────────────────┤
│ total run duration: 993ms                                        │
│ total data received: 2.35kB (approx)                             │
│ average response time: 100ms [min: 9ms, max: 340ms, s.d.: 120ms] │
└──────────────────────────────────────────────────────────────────┘
```

| Folder | Request | HTTP | Assertions | Notes |
| --- | --- | --- | --- | --- |
| **Auth** | `POST /api/auth/login` — demo user | 200 | 3 | Returns three-segment JWT; `token_type=Bearer`; `expires_in > 0`; `scope` contains `invoice:write`. Stores `access_token` on collection variable. |
| **Auth** | `POST /api/auth/login` — wrong password | 401 | 2 | `codigo=INVALID_CREDENTIALS`, `mensagem` populated. |
| **Happy paths** | `POST /api/orders/generate-invoice` — FISICA (`teste-pf.json`) | 200 | 6 | Snake_case Portuguese keys present; FISICA totalItemsValue=100 < 500 → tax = 0; SUDESTE freight = 10.0 × 1.048 = 10.48; echoes `X-Correlation-Id`. |
| **Happy paths** | `POST /api/orders/generate-invoice` — JURIDICA SIMPLES_NACIONAL (`teste-pj-simples.json`) | 200 | 5 | JURIDICA SIMPLES totalItemsValue=5840 > 5000 → rate 0.19 → tax = 138.7; SUDESTE freight = 72 × 1.048 = 75.46 (HALF_EVEN scale 2); no English keys leak into response. |
| **Happy paths** | `POST /api/pedido/gerarNotaFiscal` — legacy alias | 200 | 2 | Same contract as the canonical endpoint. |
| **Rejections** | `POST /api/orders/generate-invoice` — JURIDICA + OUTROS | 400 | 2 | `codigo=UNSUPPORTED_TAX_REGIME`. |
| **Rejections** | `POST /api/orders/generate-invoice` — JURIDICA without `taxRegime` | 400 | 2 | `codigo=INVALID_TAX_REGIME`. |
| **Rejections** | `POST /api/orders/generate-invoice` — `regiao = null` | 400 | 2 | `codigo=INVALID_DELIVERY_REGION`. |

Pre-request script behaviour: the collection-level `prerequest` event runs before every request. If `pm.collectionVariables.get('accessToken')` is empty, it `pm.sendRequest()` to `/api/auth/login` with the demo user, parses the response, and stores `access_token`. The script short-circuits when the outgoing request itself is `/api/auth/login` to avoid recursion. This means every protected request — including a cold start of `newman run` — gets a valid token without any setup step.

### Failure-mode diagnostics

| Failure shape | Likely cause | Remedy |
| --- | --- | --- |
| All 24 assertions pass | n/a | n/a |
| 401 on the first happy-path request, with body `{"codigo":"UNAUTHORIZED"}` | Pre-request script failed (app not reachable, or login returned non-200). Check `console.error` lines in the Newman output. | Ensure the app is up (`curl /actuator/health`); ensure no firewall blocks `localhost:8080`. |
| 500 on every happy-path request, rejections still 400, auth still 200/401 | Kafka producer can't reach a broker (default `localhost:9092`). The 5 protected POSTs hang ~10 s each waiting for the producer's `delivery.timeout.ms`. | `docker compose up -d kafka` and either rerun with the compose-network app, or restart the local app with `KAFKA_BOOTSTRAP_SERVERS=localhost:29092`. |
| `connect ECONNREFUSED 127.0.0.1:8080` on all 8 requests | App not running. | `docker compose up -d` or `./mvnw spring-boot:run`. |
| `newman: command not found` | Newman not installed. | Use `npx --yes newman run ...` (no global install needed). |
