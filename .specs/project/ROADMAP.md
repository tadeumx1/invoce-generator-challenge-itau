# Roadmap

**Current Milestone:** M3 — Operations (complete)
**Status:** F-RESILIENCE + F-OBSERVABILITY + F-AWS + F-DEPLOY-ACTION complete (2026-05-23). Roadmap milestones M1, M2, M3 all closed.

This roadmap reflects the user-confirmed sequencing: **safety net → upgrade → Clean Architecture → defect fixes → operations**. Each feature has an ID used everywhere else (`CONCERNS.md`, spec files, commit messages).

---

## M1 — Quality foundation

**Goal:** make the codebase safe to refactor. Real tests, modern toolchain, Clean Architecture in place. At the end of M1 nothing visible to the API has changed; everything underneath has.
**Target:** completion of features F-SAFETY-NET, F-UPGRADE, F-CLEAN (in that order).

### Features

**F-SAFETY-NET — Real test suite covering the documented business rules** — COMPLETE (2026-05-22, 53 tests passing + 1 slow on-demand; JaCoCo HTML report wired)

- Unit tests for every tax bracket × person-type × tax-regime combination, with explicit assertions at the bracket edges (500 / 1000 / 2000 / 3500 / 5000).
- Unit tests for freight multiplier per region (5 cases) and the missing-region fallthrough (currently zeros — keep red, fix in M2).
- A characterization test for the static-list accumulation bug (asserts the bug, will flip to assert-no-leak in M2).
- An integration test through `InvoiceController` end-to-end with the two `payloads/` JSON fixtures.
- Drop the misleading `@Mock` in `InvoiceGeneratorServiceImplTest` (C-5). Wire the calculator as a constructor dep on the service so it's actually mockable.

**F-UPGRADE — Java 21 + Spring Boot 3.x** — COMPLETE (2026-05-23, Spring Boot 3.5.14 + Java 21; `./mvnw verify` green)

- Bumped `<java.version>` to 21 and parent to `spring-boot-starter-parent` 3.5.14.
- Confirmed no `javax.*` imports existed in app/test code, so no `jakarta.*` migration was needed.
- Spring Boot 3.5.14 dependency management provides a JDK-21-compatible Lombok.
- Added Spotless + google-java-format and a Checkstyle import policy.
- Documented `./mvnw verify` as the build/CI gate in `README` and `CLAUDE.md`.

**F-CLEAN — Clean Architecture refactor: use cases + adapters** (user-requested) — COMPLETE (2026-05-23, `./mvnw verify` + `./mvnw test -Pslow` green)

- Introduced `domain/`, `application/`, and `adapter/` layers.
- Extracted `GenerateInvoiceUseCase` / `GenerateInvoiceInteractor`.
- Defined outbound ports in `domain/port` and adapter implementations in `adapter/integration`.
- Replaced internal `new XxxService()` orchestration with constructor-injected ports wired by `ApplicationBeanConfig`.
- Moved JSON contract annotations to `adapter/web/dto`, keeping Spring/Jackson out of `domain/` and `application/`.
- Replaced tax-rate nested `if/else` chains with `switch` dispatch and reusable bracket tables.
- Replaced the freight multiplier `if/else` ladder with a `switch` and made delivery-region extraction null-safe.
- Preserved known legacy defects for M2 characterization: C-1, C-2, C-4, C-6, and the C-3 missing-region policy gap. The null-region NPE path was removed; null region now follows the existing freight `0.0` fallback.

---

## M2 — Functional defect closure

**Goal:** every functional defect in `CONCERNS.md` C-1…C-4 closed, then the remaining slow-path defect C-6 addressed with an operationally safe async/resilience design.
**Target:** completion of F-DEFECTS-FUNCTIONAL, F-DEFECTS-PERFORMANCE.

### Features

**F-DEFECTS-FUNCTIONAL — Close C-1, C-2, C-3, C-4** — COMPLETE (2026-05-23, 56 fast tests + slow profile + `./mvnw verify` green)

- C-1: `LegacyProductTaxRateCalculator` is stateless; every call returns a fresh invoice-item list.
- C-2: `JURIDICA + OUTROS` and null tax regime now reject with HTTP 400 (`UNSUPPORTED_TAX_REGIME` / `INVALID_TAX_REGIME`).
- C-3: missing delivery address or null delivery region now rejects with HTTP 400 (`INVALID_DELIVERY_REGION`).
- C-4: domain and DTO monetary fields now use `BigDecimal`; calculated tax and freight round to scale 2 with `HALF_EVEN`. JSON stays numeric on the wire.

**F-DEFECTS-PERFORMANCE — Close the +5s-on-6-items trap (C-6) with Kafka async dispatch** — COMPLETE (2026-05-23, `./mvnw verify` + `./mvnw test -Pslow` green; 64 fast tests including EmbeddedKafka end-to-end; Dockerfile + docker-compose KRaft Kafka local stack)

- Messaging decision: use Kafka for this project/roadmap because the user chose it for the implementation exercise and wants local Kafka via Docker Compose. Architecture note: for a lean AWS production version of this exact workflow, SQS would likely be the simpler fit because these are independent command-style side effects that need decoupling, retry, and DLQ, not stream replay or multi-subscriber event processing.
- Deployment boundary decision:
  - Technical-test/local implementation: publisher and consumers live in this same Spring Boot codebase so the full Kafka flow can be demonstrated with `docker compose up`.
  - Ideal production implementation: the invoice-generator service only calculates the invoice and publishes events. Stock, fiscal registration, delivery, and accounts-receivable services own their consumers and downstream integration rules.
- HTTP response contract for `POST /api/orders/generate-invoice`: return the generated invoice synchronously after domain calculation and successful Kafka publication of the required integration events. The response means "invoice generated and downstream processing requested", not "stock, fiscal registration, delivery, and finance have completed".
- Treat every current `Thread.sleep(...)` integration stub as a simulated external asynchronous service call. These sleeps must stay as simulation signals; the fix is to move the call boundary, not delete the delay.
- Replace the direct request-thread calls below with Kafka-backed asynchronous dispatch:
  - `stockPort.sendInvoiceForStockDeduction(invoice)`
  - `invoiceRegistrationPort.registerInvoice(invoice)`
  - `deliveryPort.scheduleDelivery(invoice)`
  - `accountsReceivablePort.sendInvoiceToAccountsReceivable(invoice)`
- Do **not** use detached fire-and-forget threads as the production answer. Publish durable Kafka messages after invoice generation and let consumers call the external adapters.
- Kafka topology planned for this feature:
  - `invoice.stock-deduction.v1` — 3 partitions, key `invoiceId` or `orderId`, consumed by `StockDeductionConsumer` in group `invoice-generator-stock-deduction`.
  - `invoice.registration.v1` — 3 partitions, key `invoiceId` or `orderId`, consumed by `InvoiceRegistrationConsumer` in group `invoice-generator-registration`.
  - `invoice.delivery-scheduling.v1` — 3 partitions, key `invoiceId` or `orderId`, consumed by `DeliverySchedulingConsumer` in group `invoice-generator-delivery-scheduling`.
  - `invoice.accounts-receivable.v1` — 3 partitions, key `invoiceId` or `orderId`, consumed by `AccountsReceivableConsumer` in group `invoice-generator-accounts-receivable`.
  - Each main topic gets retry topics `<topic>.retry.1m`, `<topic>.retry.5m`, `<topic>.retry.30m`, and a dead-letter topic `<topic>.dlt`.
- Add local container support for the Kafka flow:
  - Create a production-shaped `Dockerfile` for the Spring Boot application.
  - Create a `docker-compose.yml` that starts Kafka and the invoice-generator application together.
  - The compose stack must configure the application bootstrap servers for the Kafka container and allow local HTTP testing on port 8080.
  - Topic creation must be automated or documented in the compose setup so a fresh local run can publish and consume the four integration events.
- Add retry behavior for unavailable downstream services: retry with backoff while the service is unavailable, preserve the message until processed, and route exhausted failures to a dead-letter topic for investigation/replay.
- Keep idempotency in scope for consumers so Kafka redelivery/retry cannot duplicate stock deduction, invoice registration, delivery scheduling, or accounts-receivable posting.
- Add response/status wording or metadata if the API contract can evolve later, e.g. `PROCESSING` / `DISPATCHED`, but keep the current JSON payload unchanged for this challenge unless explicitly approved.
- Verify p99 latency for orders with > 5 items collapses from > 5500 ms to under 1500 ms.

---

## M3 — Operations

**Goal:** the service is deployable, observable, and resilient under failure.
**Target:** completion of F-RESILIENCE, F-OBSERVABILITY, F-AWS.

### Features

**F-RESILIENCE — Resilience4j circuit breakers on outbound adapters** — COMPLETE (2026-05-23, `./mvnw verify` + `./mvnw test -Pslow` green; 65 fast tests including CircuitBreakerLifecycleTest; C-8 closed)

- Timeout + retry-with-backoff + circuit breaker on every outbound port.
- Per-adapter SLA configured in `application.yml`; documented in `INTEGRATIONS.md`.
- Kafka retry topics / dead-letter topics defined for each async integration event.
- Fallback behavior defined for each (e.g., DeliveryPort failing keeps the Kafka message retryable or routes it to DLQ; it does not fail the original HTTP request).
- Resilience metrics exported (see F-OBSERVABILITY).

**F-OBSERVABILITY — Logs, metrics, tracing, and SLIs** — COMPLETE (2026-05-23, `./mvnw verify` green; 88 fast tests including `MetricsIntegrationTest`, `HttpTracePropagationIntegrationTest`, `CardinalityGuardTest`; `docs/observability.md` is the operator-facing SSOT; Jaeger added to docker-compose)

- Structured JSON logs via `logstash-logback-encoder`. MDC carries `correlationId`, `traceId`, `spanId`, and (when known) `invoiceId` / `orderId`. `X-Correlation-Id` header is adopted on inbound and propagated on Kafka headers.
- Micrometer metrics emitted as raw signals; **the four SLIs are computed in the metrics backend**, not in code:
  - **SLI-1 API success rate** — `http.server.requests` (status ≠ 5xx). Initial SLO: 99.5 % / 30d.
  - **SLI-2 API latency** — `http.server.requests` histogram with SLO buckets at 300 ms / 800 ms / 2 s. Initial SLO: 99 % < 800 ms.
  - **SLI-3 Kafka dispatch success** — custom `invoice.dispatch{outcome}` counter. Initial SLO: 99.9 % / 7d.
  - **SLI-4 Side-effect end-to-end latency** — custom `invoice.sideeffect.duration` timer (producer-publish → consumer-ack). Initial SLO: 95 % < 30s per integration.
- Business metrics: `invoice.generated{tax_regime, region, person_type, large_order}` and `invoice.rejected{reason}`. `orderId` / `invoiceId` / `correlationId` are **never** metric tags.
- Spring Kafka, Resilience4j, and producer/consumer Micrometer bindings expose lag, retry counts, DLQ counts, circuit-breaker state with no extra code (depends on F-DEFECTS-PERFORMANCE and F-RESILIENCE for the underlying components).
- OpenTelemetry SDK + auto-instrumentation. Producer/consumer spans propagate W3C `traceparent` over Kafka headers so a single trace spans HTTP → use case → Kafka producer → Kafka consumer.
- Backend split mirroring AD-015: local Docker Compose uses Prometheus scrape + OTLP → Jaeger; AWS production uses Micrometer CloudWatch registry + ADOT collector → X-Ray. Same instrumentation code; profile-scoped registry/exporter dependency.
- Deliverables include `docs/observability.md` with the SLI catalog, Prometheus queries per SLI, and runbook entries; F-AWS reuses the same SLI definitions for CloudWatch dashboards/alarms.

**F-POSTMAN — Postman collection for the HTTP API** — COMPLETE (2026-05-23, quick task `.specs/quick/001-postman-collection`; `docs/postman/invoice-generator.postman_collection.json` + `docs/postman/README.md`; 6 requests, every one with `pm.test()` assertions; runs in Postman or via `npx newman`)

- Two happy-path requests mirroring the shipped sample payloads (`teste-pf.json`, `teste-pj-simples.json`) and one legacy-alias request hitting `POST /api/pedido/gerarNotaFiscal`.
- Three rejection-path requests proving HTTP 400 + `codigo` contract for `UNSUPPORTED_TAX_REGIME`, `INVALID_TAX_REGIME`, `INVALID_DELIVERY_REGION` (the three codes enumerated in `RejectionCode`).
- Every request injects `X-Correlation-Id` so the F-OBSERVABILITY MDC / trace correlation is exercised on every Postman call.
- Single collection variable `baseUrl` (default `http://localhost:8080`) — no separate environment file needed.

**F-AWS — AWS deployment proposal + Terraform IaC** — COMPLETE (2026-05-23, `terraform fmt -recursive -check + init -backend=false + validate` green across 5 modules; 36 spec requirements ticked; reviewer-facing write-up at `docs/aws-architecture.md`)

- Proposal-grade Terraform (per the 2026-05-23 user clarification): HCL validates clean but is intentionally not applied against a real AWS account. Going from validate-clean to applyable is one `backend.tf` + IAM bootstrap away; documented in `infra/terraform/README.md`.
- Architecture: API Gateway HTTP API → VPC Link → internal ALB → ECS Fargate (2 tasks) → MSK (3 × `kafka.t3.small`, SASL/IAM, KMS-encrypted at rest). Observability: CloudWatch Logs (3 log groups) + Micrometer CloudWatch registry pushing to namespace `InvoiceGenerator` + ADOT sidecar → X-Ray (10% sampling).
- 5 modules under `infra/terraform/modules/`: `network` (VPC + 3+3 subnets + SGs), `msk` (cluster + KMS + broker logs), `ecs` (ECR + cluster + task def with ADOT sidecar + ALB + two scoped IAM roles), `api-gateway` (HTTP API + VPC Link + JSON access logs echoing `X-Correlation-Id`), `observability` (4-SLI CloudWatch dashboard + 4 alarms + X-Ray group + sampling rule).
- The four SLIs from `docs/observability.md` are re-expressed verbatim as CloudWatch metric math — single source of truth, two query languages.
- AuthN/AuthZ documented at the gateway boundary (Cognito User Pool vs external JWT verifier comparison) — *documented only*, not provisioned, per scope. ADR-032 (in `docs/aws-architecture.md`) captures both paths; `docs/auth-strategy.md` expands the edge-validates-services-trust pattern.
- Order-of-magnitude monthly cost at idle: ~US$ 300 (dominated by MSK ~US$ 200; documented tightening levers include MSK Serverless + VPC interface endpoints).

**F-DEPLOY-ACTION — GitHub Actions AWS deploy pipeline (proposal-grade)** — COMPLETE (2026-05-23, `.github/workflows/deploy-aws.yml` 175 lines, three jobs, OIDC; YAML parses clean via `ruby -ryaml`; F-AWS Terraform gate stays green after the two new root outputs)

- Closes the gap F-AWS explicitly left open ("CI/CD pipeline ... a separate concern"). Same proposal-grade posture as F-AWS: the YAML validates as runnable GitHub Actions but the `on:` triggers are commented out with an inert `workflow_dispatch` placeholder so nothing fires against a live account.
- Three-job pipeline: `build-and-test` (`./mvnw verify` + JaCoCo artifact) → `terraform-apply` (`fmt -recursive -check` → `init` with S3+DynamoDB backend from repo vars → `validate` → `plan` uploaded as artifact → `apply tfplan`) → `docker-deploy` (Buildx `linux/amd64` push to ECR tagged `${{ github.sha }}` + `:latest` with GHA layer cache → live task-def re-render via `aws ecs describe-task-definition` + `jq` swap → `aws-actions/amazon-ecs-deploy-task-definition@v2` with `wait-for-service-stability: true` → `curl -fsS` smoke test against the API Gateway endpoint using `payloads/teste-pf.json`).
- AWS auth via GitHub OIDC (`permissions: id-token: write` + `aws-actions/configure-aws-credentials@v4` with `role-to-assume`); no `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` referenced anywhere. Required repo inputs: secrets `AWS_DEPLOY_ROLE_ARN` + `AWS_ACCOUNT_ID`, variables `AWS_REGION` + `TF_STATE_BUCKET` + `TF_STATE_LOCK_TABLE` + `ENVIRONMENT`.
- Concurrency-guarded (`group: deploy-aws-${{ github.ref }}, cancel-in-progress: false`) so two pushes to the same ref cannot race a deploy.
- Two new root Terraform outputs added (`ecs_cluster_name`, `ecs_service_name`) so the pipeline reads cluster/service names via `terraform output -raw` rather than re-deriving naming conventions.
- AD-031 in `STATE.md` records the design decisions (OIDC over keys, commented triggers, rolling deploy over blue/green, live task-def re-render over template injection).

---

## Future Considerations

- Real SEFAZ / fiscal registry integration (today only stubbed).
- Multi-tenant invoice numbering / idempotency keys (`Idempotency-Key` header).
- Webhook receiver for delivery system callbacks.
<!-- C-7 (paylods → payloads rename) closed 2026-05-23; removed from Future Considerations. -->

- Coverage gate in CI (e.g., JaCoCo + minimum thresholds per layer per `TESTING.md`).
- Auth implementation if scope expands.
