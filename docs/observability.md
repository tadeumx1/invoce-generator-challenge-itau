# Observability — Operator Guide

This document is the operator-facing reference for the F-OBSERVABILITY work: what the
service emits, how to compute each SLI from the raw signals, and where to look first when
an SLI burns budget. The full spec lives at
[`.specs/features/observability/spec.md`](../.specs/features/observability/spec.md); the
design notes at [`.specs/features/observability/design.md`](../.specs/features/observability/design.md).

---

## What the service exposes

| Surface | URL (local) | What it serves |
| --- | --- | --- |
| Application HTTP | `http://localhost:8080/api/orders/generate-invoice` | Business endpoint. |
| Actuator management | `http://localhost:8080/actuator/{health,info,prometheus,metrics}` | Health probes + Prometheus scrape. |
| Jaeger UI | `http://localhost:16686` | OTLP traces (HTTP server span → `invoice.generate` → 4 × Kafka producer spans → Kafka consumer spans). |
| Kafka external listener | `localhost:29092` | Host-side `kafka-console-consumer`, etc. |

`docker compose up --build` starts the full local stack: Kafka (KRaft), Jaeger
all-in-one (OTLP HTTP on 4318, UI on 16686), and the app. The app's
`OTLP_TRACING_ENDPOINT` is wired to the Jaeger container.

Logs are single-line JSON on stdout (LogstashEncoder). Every log line emitted on the
HTTP request path carries: `@timestamp`, `level`, `logger_name`, `thread_name`,
`message`, `correlationId`, `traceId`, `spanId`, plus `invoiceId` / `orderId` when
known. The `X-Correlation-Id` request header is adopted (or generated as a UUID) and
echoed back on the response.

---

## SLI catalog (the contract)

The application emits raw counters, timers, and histogram buckets. The four SLIs are
computed in the metrics backend (Prometheus recording rule, or CloudWatch metric math
when F-AWS lands). The application never computes the ratio itself.

| # | SLI | Initial SLO | Source meter |
| --- | --- | --- | --- |
| SLI-1 | API success rate | 99.5 % / 30 d | `http_server_requests_seconds_count` |
| SLI-2 | API latency (p99 < 800 ms) | 99 % / 30 d | `http_server_requests_seconds_bucket` (SLO buckets 300 ms / 800 ms / 2 s) |
| SLI-3 | Kafka dispatch success | 99.9 % / 7 d | `invoice_dispatch_total{outcome}` |
| SLI-4 | Side-effect end-to-end latency (95 % < 30 s) | 95 % / 7 d | `invoice_sideeffect_duration_seconds_bucket` |

---

## SLI-1 — API success rate

**Question:** What fraction of HTTP requests to the invoice endpoint returned anything
other than a 5xx?

**Prometheus query:**

```promql
sum(rate(http_server_requests_seconds_count{
      uri="/api/orders/generate-invoice", status!~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count{
      uri="/api/orders/generate-invoice"}[5m]))
```

The 4xx responses (`UNSUPPORTED_TAX_REGIME`, `INVALID_TAX_REGIME`,
`INVALID_DELIVERY_REGION`) count as *valid* events that succeeded from the service's
point of view — the contract was honored, the caller sent bad data. Only 5xx (genuine
server faults) erode this SLI. The matching `invoice_rejected_total{reason}` counter
breaks 4xx down by cause for ad-hoc drill-down without polluting SLI-1.

**Runbook — what burns budget:** unhandled exceptions reaching Spring's default error
mapper (status 500), `IntegrationAdapterException` thrown back into the HTTP path
before Kafka publishes, or container OOM crashes. Start with `level=ERROR` log lines
filtered by `correlationId` of the slow window, then check
`resilience4j_circuitbreaker_state{state="open"}` — an open breaker on the synchronous
path is the most common cause of a sudden 5xx spike.

---

## SLI-2 — API latency

**Question:** What fraction of HTTP requests to the invoice endpoint completed inside
the 800 ms target?

**Prometheus query:**

```promql
sum(rate(http_server_requests_seconds_bucket{
      uri="/api/orders/generate-invoice", le="0.8"}[5m]))
/
sum(rate(http_server_requests_seconds_count{
      uri="/api/orders/generate-invoice"}[5m]))
```

The SLO buckets at 300 ms / 800 ms / 2 s are configured via
`management.metrics.distribution.slo.http.server.requests=300ms,800ms,2s`; swap `le`
to compute the inner-target (300 ms) or the alarm threshold (2 s).

**Runbook — what burns budget:** the legacy +5 s sleep for >5-item orders was moved
off-thread by F-DEFECTS-PERFORMANCE, so the synchronous path is now bounded by Kafka
publish ack latency. If SLI-2 drifts: first check
`invoice_dispatch_duration_seconds{topic}` to see if a specific topic is acking slowly,
then look at Kafka broker CPU/GC in Jaeger (`spring.kafka.template` producer spans
carry broker latency).

---

## SLI-3 — Kafka dispatch success

**Question:** What fraction of producer publish attempts to the four invoice topics
succeeded?

**Prometheus query:**

```promql
sum(rate(invoice_dispatch_total{outcome="success"}[5m]))
/
sum(rate(invoice_dispatch_total[5m]))
```

Drop the outer `sum(...)` and group `by (topic)` if a single integration is degrading
faster than the global rate (common case: only the registration topic burns budget
because the broker's `min.insync.replicas` config got tightened).

**Runbook — what burns budget:** broker unavailability (network partitions, broker
restart), topic-level ACL changes, or `delivery.timeout.ms` (10 s) firing while waiting
for `acks=all` from all in-sync replicas. The `invoice_dispatch_duration_seconds` timer
shows the latency distribution; pair with Kafka broker metrics
(`kafka.server:type=BrokerTopicMetrics`) to localize the broker.

---

## SLI-4 — Side-effect end-to-end latency

**Question:** What fraction of integration events were processed end-to-end (producer
publish → consumer ack) within 30 seconds?

**Prometheus query:**

```promql
sum by (topic) (rate(invoice_sideeffect_duration_seconds_bucket{le="30"}[5m]))
/
sum by (topic) (rate(invoice_sideeffect_duration_seconds_count[5m]))
```

`le="30"` matches the SLO target (95 % < 30 s per integration); use `le="60"` for the
secondary alarm bucket. The timer is fed by the `publishedAtEpochMillis` Kafka header,
so it survives consumer restarts and doesn't need clock-aligned servers.

**Runbook — what burns budget:** consumer lag (one of the four `@KafkaListener`
groups falling behind), `@RetryableTopic` cycling a message through the retry topics
(1m / 5m / 25m delays push the histogram into the long tail), or downstream port
adapters slowing the consumer thread. Start with the Spring Kafka lag metrics
(`kafka_consumer_fetch_manager_records_lag{topic,partition}`) and then look for the
`messaging.kafka.consumer.consumer.coordinator.requests` span in Jaeger to see where
the delay sits.

---

## Cardinality budget (why some questions can only be answered from logs)

Every metric tag carries a finite, enumerable set of values. The full table is in
`.specs/features/observability/spec.md#cardinality-rules`. The short version:

| Identifier | Logs (MDC) | Trace attribute | Metric tag |
| --- | --- | --- | --- |
| `correlationId` | yes | yes | **no** |
| `invoiceId` | yes | yes | **no** |
| `orderId` | yes | yes | **no** |
| `traceId` / `spanId` | yes (Boot auto) | yes | **no** |

A question like *"how slow was the request for order 12345?"* requires the trace
(reachable via the `correlationId` you got from the response header) or a JSON-log
query, not a Prometheus query. This is the correct design — putting any of those tags
on a Micrometer counter would push label cardinality unbounded.

Enforcement: `CardinalityGuardTest` registers every public recorder method against an
in-memory registry and fails if any meter carries one of the forbidden tags.

---

## Debug logs catalog (F-DEBUG-LOGS)

F-OBSERVABILITY ships the *MDC + JSON encoder* plumbing; F-DEBUG-LOGS layers structured
`info` / `debug` / `warn` log lines on top so a CloudWatch operator can reconstruct a
production incident from logs alone (no metrics or traces required). Every line below
inherits `correlationId`, `traceId`, `spanId`, `invoiceId`, `orderId` from the F-OBSERVABILITY
MDC. AD-020 cardinality budget is unchanged — these identifiers ride logs and trace
attributes only, never metric tags.

| Logger | Level | Message prefix | Fires when |
| --- | --- | --- | --- |
| `InvoiceController` | INFO | `invoice request received` | controller entry, per HTTP request |
| `InvoiceController` | INFO | `invoice request completed` | controller exit on HTTP 200, with `elapsedMs` |
| `ApiExceptionHandler` | WARN | `invoice request rejected` | domain rejection — HTTP 400, with `codigo` |
| `ApiExceptionHandler` | WARN | `login rejected` | auth failure — HTTP 401 / 400 |
| `ApiExceptionHandler` | INFO | `request throttled` | rate-limit AOP path — HTTP 429 (annotation route) |
| `GenerateInvoiceInteractor` | INFO | `invoice generation begin` / `complete` | use-case begin / end |
| `TaxRateTable` | DEBUG | `tax bracket selected` | bracket resolution, with `personType`, `taxRegime`, `totalItemsValue`, `rate` |
| `TaxRateTable` | INFO | `invoice rejected at tax bracket selection` | `INVALID_PERSON_TYPE` / `INVALID_TAX_REGIME` / `UNSUPPORTED_TAX_REGIME` |
| `LegacyFreightCalculator` | DEBUG | `freight calculated` | freight resolution, with `region`, `baseFreight`, `multiplier`, `adjustedFreight` |
| `LegacyFreightCalculator` | INFO | `freight rejected` | `INVALID_DELIVERY_REGION` |
| `*IntegrationAdapter` (× 4) | DEBUG | `adapter enter` / `adapter ok` | outbound port enter / exit, with `port`, `invoiceId`, `elapsedMs` |
| `*IntegrationAdapter` (× 4) | WARN | `adapter fail` | outbound port failure, with `exceptionClass`, `reason` |
| `IntegrationEventPublisher` | DEBUG | `kafka publish ok` | producer ack, with `topic`, `eventId`, `partition`, `offset` |
| `IntegrationEventPublisher` | WARN | `kafka publish fail` | producer timeout / interrupt, with `exceptionClass` |
| `ResilienceEventLogger` | WARN | `circuit breaker state transition` | CB transition to `OPEN`, with `name`, `from`, `to` |
| `ResilienceEventLogger` | INFO | `circuit breaker state transition` | CB transition to `HALF_OPEN` / `CLOSED` |
| `ResilienceEventLogger` | WARN | `bulkhead rejected` | bulkhead permit denied, with `name` |
| `RateLimitFilter` | INFO | `rate-limit tripped` | per-IP bucket rejection — HTTP 429 (filter route) |

### Runtime log-level toggling

The application package logger `br.com.itau.invoicegenerator` defaults to **INFO**. Two
ways to flip it to DEBUG without code changes:

1. **Env var at container start (requires restart):**
   ```bash
   APP_LOG_LEVEL=DEBUG docker compose up
   ```
   `logback-spring.xml` reads the env var and applies it to the application package only;
   the root level (and Spring / Kafka library logs) stays at INFO.

2. **Actuator at runtime (no restart):**
   ```bash
   curl -X POST http://localhost:8080/actuator/loggers/br.com.itau.invoicegenerator \
     -H 'Content-Type: application/json' \
     -d '{"configuredLevel":"DEBUG"}'
   ```
   POST `null` to revert to the parent level.

DEBUG roughly **6× the log volume per request** (tax-bracket + freight + 4× adapter
enter/exit + Kafka publish ok lines that are otherwise silent). Flip it for one request,
read what you need, flip it back — cardinality cost on CloudWatch storage adds up fast.

---

## Verifying the SLIs against a running app

```bash
# Boot the app and the Kafka + Jaeger stack
docker compose up --build -d

# Drive some traffic
for i in {1..30}; do
  curl -s -o /dev/null -X POST http://localhost:8080/api/orders/generate-invoice \
    -H 'Content-Type: application/json' \
    -d @src/main/resources/payloads/teste-pf.json
done

# SLI-1 source
curl -s localhost:8080/actuator/prometheus \
  | grep -E '^http_server_requests_seconds_count.*uri="/api/orders/generate-invoice"'

# SLI-2 source (300/800/2000 ms buckets)
curl -s localhost:8080/actuator/prometheus \
  | grep -E 'http_server_requests_seconds_bucket.*le="(0\.3|0\.8|2\.0)"' \
  | grep 'uri="/api/orders/generate-invoice"'

# SLI-3 source
curl -s localhost:8080/actuator/prometheus | grep -E '^invoice_dispatch_total'

# SLI-4 source (visible after the consumer acks at least one message)
curl -s localhost:8080/actuator/prometheus \
  | grep -E '^invoice_sideeffect_duration_seconds_(count|bucket)'

# Business volume
curl -s localhost:8080/actuator/prometheus \
  | grep -E '^invoice_(generated|rejected)_total'

# Trace (find the latest one in Jaeger UI)
open http://localhost:16686
```

---

## Rate-limit signals (F-RATELIMIT)

`resilience4j-micrometer` auto-publishes one set of meters per statically-named
rate-limiter instance — three instances, three series each on
`/actuator/prometheus`:

```
resilience4j_ratelimiter_available_permissions{name="auth-login"}
resilience4j_ratelimiter_available_permissions{name="invoice-generate"}
resilience4j_ratelimiter_available_permissions{name="default"}
resilience4j_ratelimiter_waiting_threads{name=...}   # always 0 (timeout-duration=0)
```

**The `name` tag never carries an IP suffix** even though the filter
synthesises a per-`(group, ip)` `RateLimiter` at runtime — `RateLimiterMeterFilter`
denies any meter whose `name` tag is not one of the three groups, preserving
the AD-020 cardinality budget. Per-IP signals stay in **logs** (filter logs
DEBUG-level decisions with `clientIp`) and **trace attributes** only.

Useful queries:

```promql
# Permits remaining per group right now.
resilience4j_ratelimiter_available_permissions

# Anything where the auth-login group is depleted for any aggregate sample.
min_over_time(resilience4j_ratelimiter_available_permissions{name="auth-login"}[5m]) == 0

# Rate of 429 responses in the last 5 minutes (relies on the Spring HTTP timer).
sum(rate(http_server_requests_seconds_count{status="429"}[5m])) by (uri)
```

The rate-limit signals are **not** an SLI — they remain queryable for ad-hoc
debugging but did not earn a row in the four-SLI catalog (AD-017 stays frozen
at four). Promoting "% of requests rejected with 429" to a fifth SLI would
re-open that decision; flagged under RLIM-OOS-5 if a future operator wants it.

---

## F-AWS reuse path

When the AWS deployment lands (F-AWS), the four queries above translate to CloudWatch
metric math with no application code change:

- The Micrometer **CloudWatch registry** publishes the same meter names under namespace
  `InvoiceGenerator/` (dots → `_`).
- A CloudWatch metric-math expression mirrors each Prometheus query —
  e.g., SLI-1 becomes
  `m1 = SUM(http_server_requests_seconds_count{status_class!="5xx"}) / SUM(http_server_requests_seconds_count)`.
- Sampling probability drops from 1.0 (local) to ~0.1 (AWS) once SLO alarms are tuned
  — captured as a follow-up under AD-021.

The doc is the SSOT for the SLI math. F-AWS dashboards/alarms must reuse the
definitions above; do not re-derive them inside Terraform.
