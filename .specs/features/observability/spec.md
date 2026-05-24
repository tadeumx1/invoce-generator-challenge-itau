# F-OBSERVABILITY — Logs, Metrics, Tracing, and SLIs Specification

## Problem Statement

The invoice-generator service today has no production-grade observability. There are no
structured logs, no `traceId`/`spanId` correlation, no application metrics, no SLIs, and no
distributed tracing. F-DEFECTS-PERFORMANCE introduces Kafka producers, consumers, retry
topics, and a dead-letter topology that will be effectively invisible without consumer-lag,
retry, and DLQ metrics. F-RESILIENCE will add timeouts, retries, and circuit breakers whose
state must be observable to be trusted. F-AWS will deploy this service to ECS/MSK where
operators expect CloudWatch logs, dashboards, alarms, and X-Ray traces.

This feature makes the service observable end-to-end: it ships structured JSON logs with
correlation across the HTTP request, the invoice, and downstream Kafka events; emits
Micrometer metrics whose **ratios and percentiles back the four agreed SLIs**; and exports
OpenTelemetry traces locally over OTLP and to AWS X-Ray in production.

## Observability Backend Decision

Two intentional backends, mirroring the dual deployment model already established for
F-DEFECTS-PERFORMANCE (AD-015):

| Context | Logs | Metrics | Traces |
| --- | --- | --- | --- |
| Local Docker Compose | JSON to stdout, collected by Docker | Micrometer Prometheus registry exposed at `/actuator/prometheus`; Prometheus container scrapes it | OpenTelemetry SDK → OTLP exporter → Jaeger (or Tempo) container |
| Ideal production (AWS, F-AWS) | JSON to stdout → CloudWatch Logs via FireLens/awslogs driver | Micrometer CloudWatch registry (or ADOT collector → CloudWatch) | OpenTelemetry SDK → ADOT collector → AWS X-Ray |

The Micrometer abstraction lets the same instrumentation code feed either backend; only the
registry dependency changes between profiles. The Spring profile boundary is the seam:
`application-local.yml` enables Prometheus + OTLP; `application-aws.yml` enables CloudWatch +
X-Ray.

## SLI / SLO Catalog (the contract)

Four SLIs are frozen in this spec. Every metric, log enrichment, and trace span exists to
serve one of them, or to debug one of them when it burns budget.

| # | SLI | Definition (good ÷ valid events) | Source signals | Initial SLO |
| --- | --- | --- | --- | --- |
| SLI-1 | **API success rate** | `count(http.server.requests{uri=/api/orders/generate-invoice, status!~5..}) / count(http.server.requests{uri=/api/orders/generate-invoice})` | `http.server.requests` (Spring auto-instrumentation) | 99.5 % rolling 30d |
| SLI-2 | **API latency** | `count(http.server.requests_bucket{uri=/api/orders/generate-invoice, le=0.8}) / count(http.server.requests_count{uri=/api/orders/generate-invoice})` | `http.server.requests` histogram + SLO buckets at 300 ms / 800 ms / 2 s | 99 % requests < 800 ms (excluding the >5-item delivery sleep window — measured *after* F-DEFECTS-PERFORMANCE moves the sleep off-thread) |
| SLI-3 | **Kafka dispatch success** | `count(invoice.dispatch{outcome=success}) / count(invoice.dispatch)` aggregated over the four topics | Custom counter `invoice.dispatch` tagged by `topic`, `outcome` | 99.9 % rolling 7d |
| SLI-4 | **Side-effect end-to-end latency** | `count(invoice.sideeffect.duration_bucket{le=30}) / count(invoice.sideeffect.duration_count)` per integration | Custom timer `invoice.sideeffect.duration` started at HTTP publish, stopped at consumer-ack | 95 % completed < 30 s per integration (post-F-DEFECTS-PERFORMANCE) |

**Calculation site:** SLIs are calculated in the metrics backend (Prometheus recording rules
or CloudWatch metric math). The application emits raw counters, timers, and histogram
buckets only — Micrometer does **not** compute the ratio.

**Cardinality budget:** tag values per metric must stay bounded. Allowed tag dimensions and
their cardinality ceiling are listed under [Cardinality rules](#cardinality-rules) below.

## Goals

- [ ] Every log line on the HTTP request path carries `correlationId`, `traceId`, `spanId`,
      and (when known) `invoiceId` and `orderId` via MDC.
- [ ] `/actuator/prometheus` returns a scrape-ready response with the four SLI source
      signals.
- [ ] An OpenTelemetry trace from `POST /api/orders/generate-invoice` shows: HTTP span →
      use-case span → Kafka producer span (× 4 outbound integrations). After
      F-DEFECTS-PERFORMANCE wires consumers, the same trace continues into consumer spans
      via Kafka context propagation.
- [ ] Each of the four SLIs is computable from emitted metrics with no application code
      changes once instrumentation lands.
- [ ] Logs are structured JSON; no `System.out.println`, no plain-text logger calls except
      explicit allowlisted format strings, no PII or full invoice payloads in logs.
- [ ] Local: `docker compose up` (from F-DEFECTS-PERFORMANCE) plus the observability
      additions shows logs in `docker compose logs`, metrics in Prometheus, traces in
      Jaeger/Tempo.
- [ ] Production wiring: documented in F-AWS Terraform (CloudWatch dashboards, X-Ray
      service map, log groups, alarm scaffolding tied to SLOs).

## Out of Scope

| Feature | Reason |
| --- | --- |
| Implementing CloudWatch alarms / PagerDuty integration | Belongs in F-AWS once SLO numbers are validated. |
| Log aggregation server (ELK / Loki) | Local Docker captures stdout; AWS uses CloudWatch. No third backend. |
| Business intelligence dashboards | Metrics are operator-facing; analytics belongs elsewhere. |
| Per-customer SLOs | This is a single-tenant service for the challenge. |
| Full RUM / browser telemetry | API-only system (per PROJECT.md scope). |
| Removing `Thread.sleep` to make metrics look better | Sleeps are simulation; F-DEFECTS-PERFORMANCE moves them off-thread. Observability measures, it does not hide. |

---

## User Stories

### P1: Structured JSON logs with correlation IDs ⭐ MVP

**User Story:** As an operator debugging a production incident, I want every log line tied to
a single HTTP request, invoice, and trace, so that I can reconstruct what happened from logs
alone when tracing is unavailable.

**Why P1:** Logs are the lowest common denominator. Without correlation, distributed bugs
are unreproducible from production logs.

**Acceptance Criteria:**

1. **WHEN** the application emits any log line on the HTTP request path **THEN** the JSON
   record SHALL include `timestamp`, `level`, `logger`, `message`, `thread`,
   `correlationId`, `traceId`, `spanId`, and (when available) `invoiceId` and `orderId`.
2. **WHEN** an HTTP request arrives without an `X-Correlation-Id` header **THEN** the
   application SHALL generate a UUID and put it in MDC for the lifetime of the request.
3. **WHEN** an HTTP request arrives with an `X-Correlation-Id` header **THEN** the
   application SHALL adopt that value into MDC and echo it back in the response.
4. **WHEN** a Kafka producer publishes an integration event **THEN** the event SHALL carry
   the `correlationId` and W3C `traceparent` as Kafka headers.
5. **WHEN** a Kafka consumer receives an event **THEN** it SHALL restore `correlationId`
   and the trace context from headers before logging or processing.
6. **WHEN** any log line is written **THEN** it SHALL be valid JSON parseable by `jq -c .`
   with no multi-line stack traces breaking the JSON structure (stack traces serialize as a
   single field).
7. **WHEN** an exception is logged **THEN** the JSON record SHALL include `exception.class`,
   `exception.message`, and `exception.stack` fields without leaking request payload bodies.

**Independent Test:**

```bash
./mvnw test -Dtest='*LoggingIntegrationTest,*CorrelationIdFilterTest'
curl -s -H 'X-Correlation-Id: probe-123' -X POST localhost:8080/api/orders/generate-invoice \
  -H 'Content-Type: application/json' -d @src/main/resources/payloads/teste-pf.json
docker compose logs app | jq -c 'select(.correlationId == "probe-123")'
```

---

### P1: Micrometer metrics backing the four SLIs ⭐ MVP

**User Story:** As an SRE, I want the application to emit the raw signals that compute the
four SLIs, so that I can wire SLOs and burn-rate alerts without changing application code.

**Why P1:** No metrics, no SLIs, no proof the system meets its own goals.

**Acceptance Criteria:**

8. **WHEN** the application starts **THEN** Spring Boot Actuator SHALL expose
   `/actuator/prometheus`, `/actuator/health`, and `/actuator/info` on a separate
   management port (default 8081) bound to the container network only.
9. **WHEN** an HTTP request to `/api/orders/generate-invoice` completes **THEN** the
   `http.server.requests` timer SHALL record latency in a histogram with SLO buckets at
   300 ms, 800 ms, and 2000 ms and percentiles 0.5 / 0.95 / 0.99.
10. **WHEN** an invoice is successfully generated **THEN** counter `invoice.generated`
    SHALL increment with tags `tax_regime`, `region`, `person_type`.
11. **WHEN** the domain rejects an invoice **THEN** counter `invoice.rejected` SHALL
    increment with tag `reason` matching the `codigo` returned to the client
    (`UNSUPPORTED_TAX_REGIME`, `INVALID_TAX_REGIME`, `INVALID_DELIVERY_REGION`, …).
12. **WHEN** a Kafka producer publishes an integration event **THEN** counter
    `invoice.dispatch` SHALL increment with tags `topic` and `outcome` ∈ {`success`,
    `failure`}.
13. **WHEN** a Kafka producer publishes an integration event **THEN** timer
    `invoice.dispatch.duration` SHALL record the producer-side publish latency tagged by
    `topic`.
14. **WHEN** a Kafka consumer acknowledges an integration event **THEN** timer
    `invoice.sideeffect.duration` SHALL record the elapsed time from producer-publish
    timestamp (Kafka header) to consumer-ack, tagged by `topic`.
15. **WHEN** Spring Kafka is wired **THEN** the default `spring.kafka.*` Micrometer
    bindings SHALL expose `kafka.consumer.fetch.manager.records.lag`,
    `kafka.consumer.records.consumed.total`, and producer batch metrics with no extra code.
16. **WHEN** any custom metric is registered **THEN** its tag values SHALL respect the
    cardinality rules in [Cardinality rules](#cardinality-rules); `orderId`, `invoiceId`,
    and `correlationId` SHALL never appear as metric tags.

**Independent Test:**

```bash
./mvnw test -Dtest='*MetricsTest,*ActuatorEndpointTest'
curl -s localhost:8081/actuator/prometheus | grep -E '^(http_server_requests_seconds|invoice_generated_total|invoice_rejected_total|invoice_dispatch_total|invoice_dispatch_duration_seconds|invoice_sideeffect_duration_seconds|kafka_consumer_fetch_manager_records_lag)'
```

The grep must return non-empty matches for each metric family after exercising the API
once.

---

### P1: Distributed tracing across HTTP and Kafka ⭐ MVP

**User Story:** As an engineer diagnosing a slow request, I want a single trace spanning
HTTP, the use case, Kafka producers, and (post F-DEFECTS-PERFORMANCE) consumers, so that I
can see exactly which leg of the request is slow.

**Why P1:** Logs and metrics alone cannot answer "where did the 400 ms go?" once Kafka
async dispatch lands.

**Acceptance Criteria:**

17. **WHEN** the application is built with the OpenTelemetry Spring Boot starter on the
    classpath **THEN** HTTP requests SHALL automatically produce a server span named
    `POST /api/orders/generate-invoice`.
18. **WHEN** `GenerateInvoiceInteractor` runs **THEN** it SHALL produce a child span named
    `invoice.generate` with attributes `tax_regime`, `region`, `person_type`, `item_count`.
19. **WHEN** a Kafka producer publishes **THEN** a `messaging.publish` span SHALL be
    produced and the W3C `traceparent` header SHALL be propagated on the outbound Kafka
    record.
20. **WHEN** a Kafka consumer receives an event **THEN** a `messaging.receive` span SHALL
    be created from the propagated context so the consumer span links to the original HTTP
    span in trace viewers.
21. **WHEN** the local Docker Compose stack is up **THEN** OTLP traces SHALL be reachable
    in a local trace viewer (Jaeger UI on port 16686 by default).
22. **WHEN** the production profile is selected **THEN** the exporter SHALL target the AWS
    Distro for OpenTelemetry (ADOT) collector endpoint configured via env var, which
    forwards spans to X-Ray.
23. **WHEN** an exception escapes a span **THEN** the span SHALL record the exception with
    `recordException(...)` and SHALL set status `ERROR`.

**Independent Test:**

```bash
docker compose up -d
curl -X POST localhost:8080/api/orders/generate-invoice -H 'Content-Type: application/json' -d @src/main/resources/payloads/teste-pf.json
open http://localhost:16686  # Jaeger UI shows trace with HTTP → invoice.generate → 4× messaging.publish spans
```

---

### P2: Business metrics on rejections and rules

**User Story:** As a product owner, I want to see how often each business rule fires (which
tax regimes are common, which rejections are common), so that I can prioritize follow-up
work.

**Why P2:** Useful for product decisions and capacity planning; not on the critical SLI
path.

**Acceptance Criteria:**

24. **WHEN** the metrics endpoint is scraped **THEN** `invoice_generated_total` SHALL be
    queryable by `tax_regime`, `region`, and `person_type` and `invoice_rejected_total`
    SHALL be queryable by `reason`.
25. **WHEN** a tax-bracket boundary is crossed (500 / 1000 / 2000 / 3500 / 5000 BRL)
    **THEN** a debug log SHALL record the bracket selected with the order subtotal and
    selected rate (correlation IDs included, no item-level detail).
26. **WHEN** an order has more than 5 items **THEN** the `invoice.generated` counter SHALL
    additionally carry tag `large_order=true`, capped to a binary value so cardinality
    does not explode.

---

### P2: Resilience and Kafka health signals

**User Story:** As an operator, I want to see Resilience4j circuit-breaker state, retry
counts, retry-topic depth, and DLQ counts, so that I can tell whether a downstream is
degraded before customers report it.

**Why P2:** Depends on F-RESILIENCE and F-DEFECTS-PERFORMANCE for the underlying components.
Instrumentation is owned by F-OBSERVABILITY; the components themselves are owned by their
home features.

**Acceptance Criteria:**

27. **WHEN** F-RESILIENCE wires Resilience4j on an outbound adapter **THEN** the standard
    Micrometer bindings (`resilience4j.circuitbreaker.state`,
    `resilience4j.circuitbreaker.calls`, `resilience4j.retry.calls`) SHALL be exposed
    without extra code.
28. **WHEN** a Kafka consumer routes a message to a retry topic **THEN** counter
    `invoice.retry` SHALL increment with tags `topic` and `retry_stage` ∈
    {`1m`, `5m`, `30m`}.
29. **WHEN** a Kafka consumer routes a message to a DLT **THEN** counter `invoice.dlt`
    SHALL increment with tag `topic`.
30. **WHEN** the consumer is healthy **THEN** the `kafka.consumer.fetch.manager.records.lag`
    gauge SHALL stay near zero for an idle topic.

---

### P3: Documentation of SLIs, dashboards, and runbooks

**User Story:** As a future maintainer, I want a single place that lists the SLIs, their
formulas, and the dashboards/queries that compute them, so that I do not have to reverse-
engineer intent from code.

**Acceptance Criteria:**

31. **WHEN** the feature is complete **THEN** `docs/observability.md` SHALL exist with the
    SLI/SLO table, the Prometheus query for each SLI, and a short runbook entry per SLI
    (what burns budget, where to look).
32. **WHEN** F-AWS is implemented **THEN** the CloudWatch dashboard JSON or Terraform shall
    reference the same SLI definitions verbatim.

---

## Edge Cases

- **WHEN** a request fails authentication or validation before reaching the controller
  **THEN** the metric and log SHALL still carry a `correlationId` generated in the filter.
- **WHEN** the application is shutting down and the metrics endpoint is scraped **THEN** the
  endpoint SHALL respond with the last known state or 503, never 500.
- **WHEN** the OTel exporter cannot reach its collector **THEN** the application SHALL log
  the failure at WARN at most every 30 s (no log flood) and continue serving HTTP
  requests; tracing failures SHALL NOT degrade the request path.
- **WHEN** Logback fails to serialize an MDC value **THEN** the JSON record SHALL still be
  emitted with the offending field omitted, never with a half-written line.
- **WHEN** a Kafka header is missing on consume **THEN** the consumer SHALL synthesize a
  new `correlationId` and log a WARN noting the original event lacked correlation.
- **WHEN** metrics cardinality would exceed the budget (e.g., a new tag is introduced
  carrying user-supplied input) **THEN** the metric SHALL be replaced or the tag dropped
  before merge; a Checkstyle/Spotless or unit test guard documents this.

---

## Cardinality Rules

To keep Prometheus and CloudWatch healthy, tag values per metric are bounded by:

| Tag | Allowed values | Cap |
| --- | --- | ---: |
| `uri` | Spring auto-instrumented templated paths only (`/api/orders/generate-invoice`, `/actuator/{name}`) — Spring's `WebMvcTagsContributor` already templatises path variables. | < 20 |
| `status` | HTTP status codes seen | < 20 |
| `tax_regime` | `SIMPLES_NACIONAL`, `LUCRO_REAL`, `LUCRO_PRESUMIDO`, `MEI`, `OUTROS` | 5 |
| `person_type` | `FISICA`, `JURIDICA` | 2 |
| `region` | `NORTE`, `NORDESTE`, `CENTRO_OESTE`, `SUDESTE`, `SUL` | 5 |
| `reason` | Domain exception codes (`UNSUPPORTED_TAX_REGIME`, `INVALID_TAX_REGIME`, `INVALID_DELIVERY_REGION`) | < 20 |
| `topic` | The four `invoice.*.v1` topics plus retry / DLT suffixes | 20 |
| `outcome` | `success`, `failure` | 2 |
| `retry_stage` | `1m`, `5m`, `30m` | 3 |
| `large_order` | `true`, `false` | 2 |

**Forbidden tags on metrics:** `orderId`, `invoiceId`, `correlationId`, `traceId`, `spanId`,
customer identifiers, any free-text fields. These belong on logs and trace attributes, not
on metric labels.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| OBS-01 | P1: JSON logs include correlation/trace/span/invoice/order IDs | Design | Planned |
| OBS-02 | P1: Generate `correlationId` when header absent | Design | Planned |
| OBS-03 | P1: Adopt and echo `X-Correlation-Id` when present | Design | Planned |
| OBS-04 | P1: Kafka producer propagates correlation + `traceparent` headers | Design | Planned |
| OBS-05 | P1: Kafka consumer restores MDC + trace context from headers | Design | Planned |
| OBS-06 | P1: Logs are single-line JSON parseable by `jq` | Design | Planned |
| OBS-07 | P1: Exception logs include class/message/stack without payload bleed | Design | Planned |
| OBS-08 | P1: Actuator exposes `/prometheus`, `/health`, `/info` on management port | Design | Planned |
| OBS-09 | P1: `http.server.requests` histogram with SLO buckets at 300/800/2000 ms | Design | Planned |
| OBS-10 | P1: `invoice.generated` counter tagged by regime/region/person | Design | Planned |
| OBS-11 | P1: `invoice.rejected` counter tagged by reason code | Design | Planned |
| OBS-12 | P1: `invoice.dispatch` counter per topic with outcome tag | Design | Planned |
| OBS-13 | P1: `invoice.dispatch.duration` timer per topic | Design | Planned |
| OBS-14 | P1: `invoice.sideeffect.duration` timer producer→consumer-ack per topic | Design | Planned |
| OBS-15 | P1: Spring Kafka Micrometer bindings expose consumer lag and totals | Design | Planned |
| OBS-16 | P1: Cardinality rules enforced; forbidden tags absent on metrics | Design | Planned |
| OBS-17 | P1: OTel auto-instrumentation produces HTTP server spans | Design | Planned |
| OBS-18 | P1: `invoice.generate` child span with regime/region/person/items attrs | Design | Planned |
| OBS-19 | P1: Kafka producer span + W3C `traceparent` injection | Design | Planned |
| OBS-20 | P1: Kafka consumer span linked via context propagation | Design | Planned |
| OBS-21 | P1: Local OTLP exporter reachable from Jaeger UI | Design | Planned |
| OBS-22 | P1: Production exporter targets ADOT/X-Ray via env var | Design | Planned |
| OBS-23 | P1: Spans record exceptions and set ERROR status | Design | Planned |
| OBS-24 | P2: Business metrics queryable by regime/region/person/reason | Design | Planned |
| OBS-25 | P2: Tax-bracket selection logged at DEBUG with correlation | Design | Planned |
| OBS-26 | P2: `large_order` binary tag on `invoice.generated` | Design | Planned |
| OBS-27 | P2: Resilience4j bindings expose CB/retry metrics | Design | Planned |
| OBS-28 | P2: `invoice.retry` counter per topic and retry stage | Design | Planned |
| OBS-29 | P2: `invoice.dlt` counter per topic | Design | Planned |
| OBS-30 | P2: Consumer-lag gauge near zero when idle | Design | Planned |
| OBS-31 | P3: `docs/observability.md` documents SLIs, queries, runbooks | Design | Planned |
| OBS-32 | P3: F-AWS dashboards reference SLI definitions verbatim | Design | Planned |

**Status values:** Planned → In Design → In Tasks → Implementing → Verified

**Coverage:** 32 total, 0 mapped to tasks yet (tasks.md created in Tasks phase).

---

## Dependencies and Sequencing

This feature has soft dependencies on two other features:

- **F-DEFECTS-PERFORMANCE** (Kafka producers/consumers): OBS-04, OBS-05, OBS-12, OBS-13,
  OBS-14, OBS-15, OBS-19, OBS-20, OBS-28, OBS-29, OBS-30. F-OBSERVABILITY can land the HTTP
  and use-case instrumentation first; Kafka-specific metrics and tracing follow when the
  producers/consumers exist.
- **F-RESILIENCE** (Resilience4j): OBS-27. F-OBSERVABILITY only enables the bindings;
  the components are owned by F-RESILIENCE.

Recommended order: **F-DEFECTS-PERFORMANCE → F-OBSERVABILITY → F-RESILIENCE → F-AWS**. This
delivers the four SLIs end-to-end before resilience hardening, and gives F-AWS a CloudWatch
dashboard target that already maps to working application metrics.

---

## Success Criteria

How we know F-OBSERVABILITY is successful:

- [ ] Every log line in `docker compose logs app` is valid JSON with `correlationId` /
      `traceId` / `spanId` enrichment.
- [ ] `curl localhost:8081/actuator/prometheus` returns the metric families listed under
      P1: Metrics.
- [ ] A single `POST /api/orders/generate-invoice` produces a Jaeger trace with at least
      HTTP server + `invoice.generate` + 4× producer spans, plus consumer spans linked via
      context propagation.
- [ ] All four SLIs are computable from emitted signals using only Prometheus queries (no
      application code change required).
- [ ] No metric tag uses an unbounded value; cardinality budget per metric verified by an
      explicit test.
- [ ] `./mvnw verify` passes (no Checkstyle / Spotless / JaCoCo regression).
- [ ] `docs/observability.md` exists and is referenced from `CLAUDE.md` and `README.md`.
