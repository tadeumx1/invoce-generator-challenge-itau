# F-OBSERVABILITY Tasks

**Design:** `.specs/features/observability/design.md`
**Spec:** `.specs/features/observability/spec.md`
**Status:** Done (2026-05-23)
**Granularity policy:** consolidated vertical slices (5 tasks). Per user preference, each
task is one atomic commit covering one coherent observability slice rather than per-file
sub-tasks. Co-located tests remain a hard rule (TESTING.md coverage matrix).

---

## Execution Plan

```
T1 (foundation) ──→ T2 (logs)     ─┐
                     │              │
                     ├──→ T3 (metrics)  ─┬──→ T5 (docs + verify)
                     │                   │
                     └──→ T4 (tracing+Kafka headers) ─┘
```

- **T1** is the dependency root: adds pom dependencies, base `application.yml` /
  `application-local.yml` skeleton, Actuator wiring, `logback-spring.xml`, and the
  Spring profile bindings. Everything downstream needs it.
- **T2, T3, T4** can run in any order after T1, but they each touch overlapping config
  files (`application.yml`, `ObservabilityConfig`), so we run them **sequentially** to
  keep diffs reviewable. `[P]` is NOT marked on them.
- **T5** wraps up `docs/observability.md`, the CLAUDE/README cross-links, and the
  end-to-end verification from spec.md §Success Criteria.

T4 has a soft dependency on F-DEFECTS-PERFORMANCE having produced Kafka producer/consumer
beans. If F-DEFECTS-PERFORMANCE is not yet merged when T4 runs, T4 lands the producer-side
plumbing (header injection, dispatch metric) and stubs consumer-side tests behind
`@Disabled("Pending F-DEFECTS-PERFORMANCE")` rather than blocking the whole feature.

---

## Task Breakdown

### T1: Observability foundation — deps, Actuator, JSON Logback, profile skeleton

**What:** Add the Micrometer / Tracing / Logback dependencies, expose the Actuator
management surface on port 8081 with `health`/`info`/`prometheus`/`metrics`, create
`logback-spring.xml` with `LogstashEncoder`, and split configuration into
`application.yml` + `application-local.yml`. After this task the app boots, exposes
`/actuator/prometheus`, and emits one JSON log line per request — but no custom MDC
enrichment, no custom metrics, no traces yet.

**Where:**

- `pom.xml` (deps + dependencyManagement entry for `logstash-logback-encoder:8.0`)
- `src/main/resources/application.yml` (new — base config block from design.md)
- `src/main/resources/application-local.yml` (new — local OTLP/Prometheus bits)
- `src/main/resources/logback-spring.xml` (new — JSON encoder)
- `src/main/java/br/com/itau/invoicegenerator/adapter/config/ObservabilityConfig.java`
  (new — empty shell class with `@Configuration`; populated by T2/T3)
- `src/test/java/.../ActuatorPrometheusIntegrationTest.java` (new)

**Depends on:** none.

**Reuses:** existing `ApplicationBeanConfig` (`@Configuration` pattern), existing
`InvoiceGeneratorApplicationTests` (Spring context test scaffolding), AD-019 / AD-021 /
AD-022 dependency decisions.

**Requirements covered:** OBS-06, OBS-08, OBS-15 (Spring Kafka observation flag — gated
behind F-DEFECTS-PERFORMANCE existing), OBS-21 (local OTLP endpoint configured even
without traces yet).

**Done when:**

- [x] `pom.xml` declares: `spring-boot-starter-actuator`,
      `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`,
      `opentelemetry-exporter-otlp`, and `net.logstash.logback:logstash-logback-encoder`
      with explicit `<version>8.0</version>` (the only pinned version; everything else
      managed by Spring Boot 3.5.14 parent).
- [x] `./mvnw spring-boot:run` exposes `/actuator/health` (200) and `/actuator/prometheus`
      (200 with `http_server_requests_*` after one warm-up request) on port 8081.
- [x] Default app port stays 8080.
- [x] All log output to stdout is valid single-line JSON parseable by `jq -c .`.
- [x] No PII or invoice payload bodies appear in logs from the test suite.
- [x] Gate check passes: `./mvnw verify`
- [x] Test count: 56 existing tests + 1 new (`ActuatorPrometheusIntegrationTest`) =
      57 tests pass (no silent deletions).

**Tests:** integration (Spring context + MockMvc against Actuator endpoints).
**Gate:** full (`./mvnw verify`).

**Verify:**

```bash
./mvnw spring-boot:run &
sleep 5
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/actuator/health   # → 200
curl -s -X POST http://localhost:8080/api/orders/generate-invoice \
  -H 'Content-Type: application/json' \
  -d @src/main/resources/paylods/teste-pf.json | jq -c .
curl -s http://localhost:8081/actuator/prometheus | grep -c '^http_server_requests'  # ≥ 1
kill %1
```

**Commit:** `feat(observability): bootstrap Actuator, Prometheus scrape endpoint, and JSON logging`

---

### T2: CorrelationIdFilter, MDC enrichment, Kafka header schema

**What:** Implement `CorrelationIdFilter` (read or generate `X-Correlation-Id`, push to
MDC, echo on response). Define the Kafka header schema (`correlationId`, `invoiceId`,
`orderId`, `publishedAtEpochMillis`) as a small constants holder. Wire MDC keys into the
`LogstashEncoder` include-list. Add the `MdcRestoringRecordInterceptor` class so that when
F-DEFECTS-PERFORMANCE lands, consumers automatically lift the headers into MDC.

**Where:**

- `adapter/web/observability/CorrelationIdFilter.java` (new)
- `adapter/integration/observability/InvoiceKafkaHeaders.java` (new — constants)
- `adapter/integration/observability/MdcRestoringRecordInterceptor.java` (new)
- `adapter/config/ObservabilityConfig.java` (extend — register filter as
  `FilterRegistrationBean` with `HIGHEST_PRECEDENCE`)
- `src/main/resources/logback-spring.xml` (extend — add includeMdcKeyName entries for
  `correlationId`, `invoiceId`, `orderId`)
- `src/test/java/.../adapter/web/observability/CorrelationIdFilterTest.java` (new)
- `src/test/java/.../adapter/web/observability/CorrelationIdHttpIntegrationTest.java`
  (new — MockMvc, asserts JSON log contains correlationId)

**Depends on:** T1.

**Reuses:** Spring's `OncePerRequestFilter`, existing
`InvoiceControllerIntegrationTest` test fixtures.

**Requirements covered:** OBS-01, OBS-02, OBS-03, OBS-05 (interceptor class exists; full
wiring with consumers in T4), OBS-06, OBS-07.

**Done when:**

- [x] `X-Correlation-Id: probe-123` on a request is echoed back on the response and
      appears as `correlationId=probe-123` in every JSON log line emitted while the
      request is processed.
- [x] A request without the header gets a generated UUID; the same UUID is echoed and
      logged.
- [x] Header value validation: only `^[A-Za-z0-9_-]{1,128}$` is accepted; longer/invalid
      values cause a fresh UUID and a WARN log.
- [x] Every log line in `mvn test` output that includes `correlationId` is parseable by
      `jq -c .` (smoke check in `CorrelationIdHttpIntegrationTest`).
- [x] `MdcRestoringRecordInterceptor` exists, is unit-tested on a fake `ConsumerRecord`
      with and without each header, and lives in the package where F-DEFECTS-PERFORMANCE
      will pick it up.
- [x] Gate check passes: `./mvnw verify`
- [x] Test count: 59 tests pass (57 from T1 + 2 new).

**Tests:** unit (`CorrelationIdFilterTest`,
`MdcRestoringRecordInterceptorTest`) + integration (`CorrelationIdHttpIntegrationTest`).
**Gate:** full.

**Verify:**

```bash
./mvnw test -Dtest='CorrelationIdFilterTest,CorrelationIdHttpIntegrationTest,MdcRestoringRecordInterceptorTest'
```

**Commit:** `feat(observability): correlation IDs via MDC, Kafka header schema, logback enrichment`

---

### T3: InvoiceMetricsRecorder, HTTP histogram, business metrics, cardinality guard

**What:** Implement `InvoiceMetricsRecorder` with the typed recorder methods from
design.md (Components). Wire it from `InvoiceController` (success path) and
`ApiExceptionHandler` (rejection path). Add the `MeterRegistryCustomizer` that puts SLO
buckets on `http.server.requests`. Add a cardinality-guard unit test that
introspects the registered meters' tag values and fails if any forbidden tag
(`orderId`, `invoiceId`, `correlationId`, `traceId`, `spanId`) is present.

**Where:**

- `adapter/observability/InvoiceMetricsRecorder.java` (new)
- `adapter/observability/RejectionCode.java` (new — enum of allowed rejection codes;
  cross-referenced from `ApiExceptionHandler`)
- `adapter/web/InvoiceController.java` (modify — inject + call recorder)
- `adapter/web/ApiExceptionHandler.java` (modify — inject + call recorder)
- `adapter/config/ObservabilityConfig.java` (extend — `httpServerRequestsHistogram`
  + `commonTags` beans)
- `src/test/java/.../adapter/observability/InvoiceMetricsRecorderTest.java` (new)
- `src/test/java/.../adapter/observability/CardinalityGuardTest.java` (new)
- `src/test/java/.../adapter/web/MetricsIntegrationTest.java` (new — MockMvc,
  scrape `/actuator/prometheus`, assert `invoice_generated_total{tax_regime="…"}` and
  `invoice_rejected_total{reason="…"}` lines)

**Depends on:** T1 (Actuator + Prometheus registry must be present; T2 is not strictly
required, but if T2 has merged the recorder calls happen inside a request whose MDC is
already populated — better logs at no extra cost).

**Reuses:** `TaxRegime`, `Region`, `PersonType` enums; existing
`InvalidInvoiceOrderException.codigo` field.

**Requirements covered:** OBS-09, OBS-10, OBS-11, OBS-16, OBS-24, OBS-26.

**Done when:**

- [x] After one successful POST, `/actuator/prometheus` returns
      `invoice_generated_total{tax_regime, region, person_type, large_order}`.
- [x] After one rejected POST (e.g., `JURIDICA + OUTROS`), `/actuator/prometheus`
      returns `invoice_rejected_total{reason="UNSUPPORTED_TAX_REGIME"} ≥ 1`.
- [x] `http_server_requests_seconds_bucket{le="0.3"|"0.8"|"2.0"}` lines exist for
      `uri="/api/orders/generate-invoice"`.
- [x] `CardinalityGuardTest` enumerates every meter and asserts none has a tag named in
      the forbidden list.
- [x] `RejectionCode` enum is the single source of truth used by both
      `ApiExceptionHandler` and `InvoiceMetricsRecorder`; a parameterised test asserts
      every value emitted by the handler is in the recorder's allow-list.
- [x] Gate check passes: `./mvnw verify`
- [x] Test count: 62 tests pass (59 from T2 + 3 new).

**Tests:** unit (`InvoiceMetricsRecorderTest`, `CardinalityGuardTest`) + integration
(`MetricsIntegrationTest`).
**Gate:** full.

**Verify:**

```bash
./mvnw test -Dtest='InvoiceMetricsRecorderTest,CardinalityGuardTest,MetricsIntegrationTest'
./mvnw spring-boot:run &
sleep 5
curl -s -X POST localhost:8080/api/orders/generate-invoice -H 'Content-Type: application/json' -d @src/main/resources/paylods/teste-pf.json >/dev/null
curl -s localhost:8081/actuator/prometheus | grep -E '^(invoice_generated_total|invoice_rejected_total|http_server_requests_seconds_bucket\{.*uri="/api/orders/generate-invoice")'
kill %1
```

**Commit:** `feat(observability): InvoiceMetricsRecorder, SLO buckets, business metrics, cardinality guard`

---

### T4: OTel tracing + Kafka producer/consumer instrumentation

**What:** Enable Micrometer Tracing's OTel bridge end-to-end. Add the `invoice.generate`
child span at the use-case boundary (created by a thin adapter wrapper or via
`@Observed`, NOT in `application/` per AD-009 — the wrapper goes in
`adapter/observability/UseCaseObservation.java`). Turn on
`spring.kafka.template.observation-enabled` / `spring.kafka.listener.observation-enabled`.
Add the producer-side instrumentation to populate Kafka headers (`correlationId`,
`invoiceId`, `orderId`, `publishedAtEpochMillis`) and to record `invoice.dispatch` /
`invoice.dispatch.duration`. Add the consumer-side `invoice.sideeffect.duration` timer
using the publish-timestamp header.

If F-DEFECTS-PERFORMANCE has not yet landed, this task adds: the OTel tracing wiring
(works on HTTP and use-case spans), the `UseCaseObservation` wrapper, the
`KafkaHeaderEnricher` class (callable by any future producer), and the
`MdcRestoringRecordInterceptor` from T2 is given final wiring. Kafka-specific tests are
`@EnabledIf` the producer beans exist, OR are added as
`@Disabled("Pending F-DEFECTS-PERFORMANCE")` with a TODO marker.

**Where:**

- `adapter/observability/UseCaseObservation.java` (new — wraps the use-case call,
  creates the `invoice.generate` span)
- `adapter/web/InvoiceController.java` (modify — call through `UseCaseObservation`)
- `adapter/integration/observability/KafkaHeaderEnricher.java` (new — `ProducerInterceptor`
  or a thin helper that sets the four headers + emits `invoice.dispatch` /
  `invoice.dispatch.duration`)
- `adapter/integration/*/[StockProducer|RegistrationProducer|DeliveryProducer|AccountsReceivableProducer].java`
  (modify or annotate, depending on what F-DEFECTS-PERFORMANCE produced)
- `adapter/integration/observability/SideEffectTimingConsumerListener.java` (new —
  `RecordInterceptor` recording `invoice.sideeffect.duration` per topic from the
  `publishedAtEpochMillis` header on consumer-success)
- `src/main/resources/application.yml` (extend — `spring.kafka.template.observation-enabled=true`, `spring.kafka.listener.observation-enabled=true`)
- `src/main/resources/application-local.yml` (extend — `management.otlp.tracing.endpoint=http://jaeger:4318/v1/traces`)
- `docker-compose.yml` (extend — add Jaeger service when F-DEFECTS-PERFORMANCE's compose
  exists; otherwise add a follow-up todo)
- `src/test/java/.../adapter/observability/UseCaseObservationTest.java` (new)
- `src/test/java/.../adapter/integration/observability/KafkaHeaderEnricherTest.java` (new — verifies headers are set and counters incremented)
- `src/test/java/.../tracing/HttpTracePropagationIntegrationTest.java` (new —
  asserts `traceId` and `spanId` are populated by Micrometer Tracing and reachable via
  MDC)

**Depends on:** T2 (header constants), T3 (recorder + cardinality guard for new dispatch
tags). Soft dependency on F-DEFECTS-PERFORMANCE for the Kafka producers/consumers.

**Reuses:** Existing producer/consumer beans from F-DEFECTS-PERFORMANCE (when present);
Spring Kafka's Observation API; Micrometer Tracing API.

**Requirements covered:** OBS-04, OBS-05 (final wiring), OBS-12, OBS-13, OBS-14,
OBS-17, OBS-18, OBS-19, OBS-20, OBS-21, OBS-22 (env-var-driven), OBS-23, OBS-28
(stub; finalised under F-RESILIENCE), OBS-29 (stub; finalised under F-RESILIENCE), OBS-30.

**Done when:**

- [x] A request with a known `correlationId` produces log lines that ALL carry the
      same `traceId` (asserted in `HttpTracePropagationIntegrationTest`).
- [x] When run under `docker compose up` with Jaeger available, the Jaeger UI shows
      a trace with at least: HTTP server span → `invoice.generate` child span.
- [x] When F-DEFECTS-PERFORMANCE producers are wired, the trace also shows 4 ×
      `messaging.publish` child spans, each carrying the `traceparent` header.
- [x] `invoice_dispatch_total{topic, outcome}` and `invoice_dispatch_duration_seconds`
      appear on `/actuator/prometheus` after a successful POST when producers exist;
      when they don't, the test is `@Disabled("Pending F-DEFECTS-PERFORMANCE")` with
      a clear TODO comment pointing at the producer adapter class.
- [x] `invoice_sideeffect_duration_seconds{topic}` exists on the consumer-side
      (same conditional disable rule).
- [x] Gate check passes: `./mvnw verify`
- [x] Test count: 65 tests pass (62 from T3 + 3 new), plus N disabled tests where N
      equals the count of Kafka-dependent assertions that defer to F-DEFECTS-PERFORMANCE.

**Tests:** unit (`UseCaseObservationTest`, `KafkaHeaderEnricherTest`) + integration
(`HttpTracePropagationIntegrationTest`).
**Gate:** full.

**Verify:**

```bash
./mvnw test -Dtest='UseCaseObservationTest,KafkaHeaderEnricherTest,HttpTracePropagationIntegrationTest'
# After F-DEFECTS-PERFORMANCE and docker compose up (with Jaeger):
docker compose up -d
curl -s -X POST localhost:8080/api/orders/generate-invoice -H 'Content-Type: application/json' -d @src/main/resources/paylods/teste-pf.json >/dev/null
open http://localhost:16686  # find the trace by correlationId/traceparent
```

**Commit:** `feat(observability): OTel tracing + Kafka header propagation + dispatch/side-effect timers`

---

### T5: docs/observability.md + cross-links + end-to-end SLI verification

**What:** Write `docs/observability.md` with: the four SLI definitions, the Prometheus
query that computes each SLI from the emitted meters, a one-paragraph runbook entry per
SLI ("what burns budget, where to look first"), and a snippet of how F-AWS will reuse
the same definitions in CloudWatch metric math. Cross-link the doc from `CLAUDE.md` and
`README.md`. Run the spec.md §Success Criteria checklist end-to-end and tick boxes in
this tasks.md.

**Where:**

- `docs/observability.md` (new)
- `CLAUDE.md` (modify — already has the Observability section; add a one-line link to
  `docs/observability.md`)
- `README.md` (modify — add an "Observability" section pointing at `docs/observability.md`
  and the `/actuator/prometheus` + Jaeger URLs for local exploration)
- `.specs/features/observability/tasks.md` (modify — flip Status: Draft → Done; tick
  Done-When boxes for T1..T4)
- `.specs/project/STATE.md` (modify — add quick-task entry #017
  "F-OBSERVABILITY execution complete"; update Current Work line)
- `.specs/project/ROADMAP.md` (modify — F-OBSERVABILITY PLANNED → COMPLETE, with
  completion date)

**Depends on:** T1, T2, T3, T4 (must verify the four SLIs are actually computable).

**Reuses:** spec.md §SLI catalog (verbatim text), design.md §Verification Plan, existing
ROADMAP/STATE update patterns from F-CLEAN / F-DEFECTS-FUNCTIONAL.

**Requirements covered:** OBS-31, OBS-32, plus the global "Success Criteria" checklist
from spec.md.

**Done when:**

- [x] `docs/observability.md` includes one Prometheus query per SLI, each query
      copy-pasteable into Prometheus and returning a numeric ratio against the running
      app.
- [x] Manual run: `curl ...` + Prometheus query produces a ratio in `[0, 1]` for SLI-1
      and SLI-3 against a single request.
- [x] `CLAUDE.md` Observability section now references `docs/observability.md` as the
      operator-facing detail.
- [x] `README.md` documents the two ports (`8080` app, `8081` management), the
      `/actuator/prometheus` endpoint, the local Jaeger URL, and points at
      `docs/observability.md`.
- [x] Every spec.md §Success Criteria checkbox is ticked or has a documented "blocked
      by F-DEFECTS-PERFORMANCE / F-RESILIENCE" annotation.
- [x] ROADMAP marks F-OBSERVABILITY COMPLETE.
- [x] Gate check passes: `./mvnw verify`
- [x] Test count: 65 tests pass (no new tests in T5 — pure documentation + verification).

**Tests:** none (documentation + checklist verification).
**Gate:** full.

**Verify:** human-readable doc + the seven-step plan from design.md §Verification Plan.

**Commit:** `docs(observability): SLI catalog, Prometheus queries, runbook; F-OBSERVABILITY complete`

---

## Parallel Execution Map

```
T1 (Sequential, foundational)
  │
  ▼
T2 ──→ T3 ──→ T4   (sequential — overlapping config files)
                │
                ▼
              T5   (sequential — depends on all)
```

**Why no `[P]` flags?** T2 / T3 / T4 each touch `ObservabilityConfig.java`,
`application.yml`, and (for T2/T4) `logback-spring.xml`. Running them concurrently would
cause merge conflicts in those files. The total amount of work in each task is small
enough that sequential execution is acceptable; sequencing also makes diffs easier to
review.

---

## Task Granularity Check

Per the active feedback memory ([[feedback-task-granularity]]), granularity is judged by
**vertical-slice cohesion**, not per-file.

| Task | Scope | Vertical-slice cohesion | Status |
| --- | --- | --- | --- |
| T1 | pom + Actuator + Logback + profile skeleton | Single "the app boots with JSON logs and an Actuator endpoint" slice. | ✅ Cohesive |
| T2 | CorrelationIdFilter + MDC + Kafka header constants + consumer interceptor stub | Single "correlation flows end-to-end" slice. | ✅ Cohesive |
| T3 | InvoiceMetricsRecorder + SLO buckets + cardinality guard + business metrics | Single "metrics that back SLI-1/SLI-2 + business volume" slice. | ✅ Cohesive |
| T4 | OTel tracing + Kafka producer/consumer instrumentation + dispatch / side-effect timers | Single "tracing + Kafka observability" slice. May be split if F-DEFECTS-PERFORMANCE is far from ready. | ✅ Cohesive |
| T5 | docs + cross-links + verification | Single "wrap-up + operator docs" slice. | ✅ Cohesive |

---

## Diagram-Definition Cross-Check

| Task | Depends on (body) | Diagram shows | Status |
| --- | --- | --- | --- |
| T1 | none | (root) | ✅ Match |
| T2 | T1 | T1 → T2 | ✅ Match |
| T3 | T1 (T2 soft) | T2 → T3 | ✅ Match (T2 is the hard predecessor by ordering; T3 doesn't strictly need T2's deliverables, but the diagram orders them for diff hygiene) |
| T4 | T2, T3 | T3 → T4 | ✅ Match (T2's deliverables flow transitively through T3) |
| T5 | T1, T2, T3, T4 | T4 → T5 | ✅ Match (T1..T3 flow transitively through T4) |

---

## Test Co-location Validation

Coverage matrix (from `TESTING.md`):

| Code layer | Required tests |
| --- | --- |
| Domain services | Unit |
| Application use case | Unit |
| HTTP adapter | Spring/MockMvc integration |
| Spring wiring | Context integration |

Per-task validation:

| Task | Code layer(s) created/modified | Matrix requires | Task says | Status |
| --- | --- | --- | --- | --- |
| T1 | Spring wiring (Actuator, Logback config), `pom.xml` | Context integration | integration (`ActuatorPrometheusIntegrationTest`) | ✅ OK |
| T2 | HTTP adapter (`CorrelationIdFilter`), adapter utility (`MdcRestoringRecordInterceptor`) | Integration for HTTP layer; unit for adapter utility | unit + integration | ✅ OK |
| T3 | HTTP adapter (`InvoiceController`, `ApiExceptionHandler` modifications), adapter utility (`InvoiceMetricsRecorder`) | Integration for HTTP layer; unit for recorder | unit + integration | ✅ OK |
| T4 | HTTP adapter (`InvoiceController` modification), adapter utility (`UseCaseObservation`, `KafkaHeaderEnricher`, listener interceptor) | Integration for HTTP layer; unit for utilities | unit + integration | ✅ OK |
| T5 | Documentation only (no code layer modified) | none | none | ✅ OK |

No ❌ violations.

---

## Pre-execution Asks

Before starting T1, confirm with the user:

1. **F-DEFECTS-PERFORMANCE timing.** F-OBSERVABILITY can be executed before, after, or
   interleaved with F-DEFECTS-PERFORMANCE. The roadmap puts PERFORMANCE first
   (recommended). If the user wants to interleave (do T1+T2+T3 now, then go to
   F-DEFECTS-PERFORMANCE, then come back for T4+T5), that's a valid path — the spec is
   designed for it.
2. **Tools/MCPs for execution.** No special MCP is required for these tasks. The
   `tlc-spec-driven` skill itself plus standard Read/Edit/Write/Bash is enough. If the
   user later wants `mermaid-studio` for richer trace/architecture diagrams in
   `docs/observability.md`, it can be added at T5.
