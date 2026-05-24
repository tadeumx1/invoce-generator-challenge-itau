# Testing

Two complementary suites guard this service:

| Layer | Tool | Where | When to run |
| --- | --- | --- | --- |
| Unit + Spring slice/integration | JUnit 5 + Spring Boot Test | `src/test/java/**` | Every change. Gated by `./mvnw verify`. |
| End-to-end HTTP (black-box) | Postman collection driven by Newman | `docs/postman/invoice-generator.postman_collection.json` | After the service is running locally (or against any deployed environment). |

Read [`docs/business-rules.md`](business-rules.md) first if you are about to write new assertions — the tax brackets, freight multipliers, and rejection codes are the contract every test exists to protect.

## In-process tests (Maven)

The fast suite is 56 tests across unit, web-slice, and Spring integration tests. It is the default and the only suite the CI gate is required to run.

```bash
./mvnw test                   # fast suite — excludes @Tag("slow")
./mvnw verify                 # fast suite + Spotless + Checkstyle + JaCoCo
./mvnw test -Pslow            # slow characterization (SlowDeliveryCharacterizationTest)
./mvnw clean package          # full build + tests (produces the runnable jar)
```

Targeting a single class or method:

```bash
./mvnw test -Dtest=GenerateInvoiceInteractorTest
./mvnw test -Dtest=TaxRateSelectionFisicaTest#bracket0
```

Tests are organised by layer to match the Clean Architecture rules in `CLAUDE.md`:

- `service/` and `web/` — pure unit tests for `domain/`, `application/`, and the controller.
- `service/characterization/` — `@Tag("slow")` characterization of the 5-second delivery sleep; only runs under `-Pslow`.
- `adapter/security/AuthControllerIntegrationTest` + `SecurityIntegrationTest` — `/api/auth/login` happy paths and JWT enforcement on the invoice endpoints.
- `adapter/web/MetricsIntegrationTest` + `adapter/observability/ActuatorPrometheusIntegrationTest` — F-OBSERVABILITY SLI source meters.
- `adapter/integration/InvoiceKafkaFlowIntegrationTest` — full producer → consumer round-trip using `@EmbeddedKafka`.
- `tracing/HttpTracePropagationIntegrationTest` — `X-Correlation-Id` / W3C `traceparent` propagation.

Spring integration tests that do **not** need Kafka set `app.messaging.kafka.enabled=false` and load `NoOpKafkaTestConfig` to skip broker wiring; the Kafka flow test is the only one that boots an embedded broker.

Mockito is intentionally excluded from the test starter (see `pom.xml`) — every collaborator in tests is a hand-written stub or a real Spring bean. Keep it that way.

### Coverage

JaCoCo runs as part of `./mvnw verify`. The HTML report lands at `target/site/jacoco/index.html`; the XML used by CI at `target/site/jacoco/jacoco.xml`. Open the HTML and drill into `application/` and `domain/` first — the adapters are mostly integration-tested.

## End-to-end HTTP tests (Newman)

`docs/postman/invoice-generator.postman_collection.json` is the black-box suite: it logs in, calls every documented HTTP path, and asserts the JSON contract frozen in [`docs/business-rules.md`](business-rules.md) (tax brackets, freight multipliers, rejection codes, `X-Correlation-Id` echo). See [`docs/postman/DOCUMENT-POSTMAN.md`](postman/DOCUMENT-POSTMAN.md) for the per-request matrix.

The collection assumes the JWT flow from F-AUTH: the first request is `POST /api/auth/login`, which stores `access_token` on a collection variable and feeds the bearer header on every downstream request. Run it as a whole — picking a single request without the login first will fail with 401.

### Local Docker stack (full integration environment)

The fast Maven suite covers the application end-to-end via `@EmbeddedKafka` and MockMvc; no infrastructure required. The Docker stack is for manual exploration, Postman/Newman runs against a live broker, and observability tooling (Prometheus scrape endpoint + Jaeger).

```bash
docker compose up -d                         # start Kafka + Jaeger + app in background
docker compose up -d kafka                   # start ONLY Kafka, e.g. when running the app via ./mvnw spring-boot:run locally
docker compose ps                            # see container status
docker compose logs -f invoice-generator     # tail app logs
docker compose down                          # stop and remove all containers + the compose network
docker compose down -v                       # additionally wipe named volumes, if any are added later
```

When you bring up only Kafka and run the app locally with `./mvnw spring-boot:run`, point the producer at the external listener:

```bash
docker compose up -d kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./mvnw spring-boot:run
```

Ports exposed:

| Component | Port | Purpose |
| --- | --- | --- |
| App | `8080` | API, actuator health, metrics, and Prometheus scrape endpoint. |
| Kafka | `29092` | External listener for host tools and a locally run app. |
| Jaeger | `16686` | Trace UI. |
| Jaeger OTLP | `4318` | OTLP HTTP receiver used by the app container. |

For an app-only run without Docker infrastructure:

```bash
./mvnw spring-boot:run
```

Wait for the actuator health endpoint to come up before launching Newman:

```bash
curl -sS http://localhost:8080/actuator/health | jq -r .status   # expect "UP"
```

### Run with Newman

```bash
# whole collection against localhost
npx newman run docs/postman/invoice-generator.postman_collection.json

# against a deployed environment
npx newman run docs/postman/invoice-generator.postman_collection.json \
  --env-var baseUrl=https://invoice.example.com

# CI-friendly: JSON + JUnit reports under target/newman/
npx newman run docs/postman/invoice-generator.postman_collection.json \
  --reporters cli,junit,json \
  --reporter-junit-export target/newman/report.xml \
  --reporter-json-export target/newman/report.json

# bail on the first failed assertion (use during local debugging)
npx newman run docs/postman/invoice-generator.postman_collection.json --bail
```

`npx` downloads Newman on demand; if you prefer a global install: `npm install -g newman`. The collection has no external environment file — `baseUrl` and `accessToken` are collection variables and `accessToken` is rewritten by the login test script on every run.

### What the collection asserts

| Folder | Request | Asserts |
| --- | --- | --- |
| Auth | `POST /api/auth/login` (demo) | 200; OAuth2-shaped body; stores `access_token` for the rest of the run. |
| Happy paths | `POST /api/orders/generate-invoice` (FISICA) | 200; `valor_frete = 10.48`; `valor_tributo_item = 0`; `X-Correlation-Id` echoed. |
| Happy paths | `POST /api/orders/generate-invoice` (JURIDICA + SIMPLES_NACIONAL) | 200; `valor_tributo_item ≈ 138.7`; `valor_frete = 75.46`. |
| Happy paths | `POST /api/pedido/gerarNotaFiscal` (legacy alias) | 200; same contract as the modern path. |
| Rejections | JURIDICA + `OUTROS` | 400; `codigo = UNSUPPORTED_TAX_REGIME`. |
| Rejections | JURIDICA without `regime_tributacao` | 400; `codigo = INVALID_TAX_REGIME`. |
| Rejections | Delivery address with `regiao = null` | 400; `codigo = INVALID_DELIVERY_REGION`. |

After at least one successful Newman run, the F-OBSERVABILITY SLI source meters are populated and observable on `http://localhost:8080/actuator/prometheus` (see [`docs/observability.md`](observability.md)). Use the `X-Correlation-Id` echoed back by each response to find the matching entries in `docker compose logs`, in Prometheus, or in Jaeger at `http://localhost:16686`.

### Troubleshooting

- **401 on every invoice request** — the login step failed (wrong host, service not up, demo user removed). Run with `--verbose` and inspect the login response; confirm `demo`/`demo123` still exists in `InMemoryUserStore`.
- **Connection refused** — the service isn't listening on `baseUrl`. Check `./mvnw spring-boot:run` output or `docker compose ps`.
- **`valor_frete` or `valor_tributo_item` assertion fails** — the business rules in [`docs/business-rules.md`](business-rules.md) are the source of truth; do not loosen the Newman assertion, fix the regression.
- **`X-Correlation-Id` missing in response** — the F-OBSERVABILITY filter or MDC propagation broke; check the `HttpTracePropagationIntegrationTest` first.

## When to run which

- Editing domain or application code → `./mvnw test` is enough.
- Editing adapters, controllers, Kafka wiring, or security → `./mvnw verify`.
- Editing the JSON contract, error codes, or any HTTP route → run Newman against a local service in addition to `./mvnw verify`. Update the collection in the same commit; reviewers rely on it being a one-click exercise of the API.
- Editing the `Thread.sleep`-bearing delivery path → run the slow profile (`./mvnw test -Pslow`).
