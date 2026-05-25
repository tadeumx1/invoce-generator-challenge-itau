# F-DEBUG-LOGS — Operator-facing debug logs across the request path

## Problem Statement

F-OBSERVABILITY landed the *plumbing* for production observability: MDC-enriched JSON logs
(AD-019, AD-022), `traceId`/`spanId` propagation (AD-021), Prometheus metrics, and OTLP
traces. What it did **not** land is wide log *coverage* across the request path. Today the
application emits log lines only at:

- four Kafka consumers (`*Consumer.java` — info on success, debug on dedupe skip),
- `CorrelationIdFilter` (warn when an inbound header is malformed),
- `SideEffectTimingConsumerListener` (warn on unknown-topic / missing-header drops),
- `MdcRestoringRecordInterceptor` (warn on missing correlation),
- `IntegrationEventPublisher` (one debug line on Kafka publish ok),
- `RateLimitFilter` (warn on missing instance config, debug on 429 trip).

The **HTTP controller, the interactor, the domain services, the four outbound integration
adapters, the exception handler, and the Resilience4j circuit breakers + bulkheads emit
nothing**. In production this means a CloudWatch operator looking at a slow request, a
rejected invoice, or a tripped circuit breaker has only metrics and traces to work from —
logs alone cannot reconstruct what happened. That breaks F-OBSERVABILITY P1's promise
("when tracing is unavailable, logs alone reconstruct the request").

This feature closes the gap: it adds **structured `info`/`debug`/`warn` log lines at every
decision point on the request path**, all of them riding the MDC / JSON / cardinality
contract already frozen by F-OBSERVABILITY. No new dependencies. No new metric tags.

## Goals

- [x] HTTP request entry and exit are logged with `orderId`, item count, response status,
      and request latency.
- [x] The use-case execution is bracketed by INFO logs noting begin/end.
- [x] Domain decisions (tax bracket selected, freight region multiplier, validation
      rejections) are logged at DEBUG (decisions) or INFO (rejections) with code +
      reason.
- [x] Outbound integration adapters log enter (DEBUG), exit-ok (DEBUG), and exit-fail
      (WARN with exception class).
- [x] Kafka producer dispatch keeps the existing DEBUG line plus a new WARN on failure.
- [x] Resilience4j circuit-breaker state transitions and bulkhead rejections are logged
      via event publishers (WARN on open / rejected, INFO on close / half-open).
- [x] Rate-limit trips are promoted from DEBUG to INFO so they surface in default
      CloudWatch log groups.
- [x] Log levels are configurable via env var so production can dial DEBUG on / off without
      a rebuild.
- [x] No payload bodies, no PII, no `valor_total_itens`-as-cents-of-customers — only
      already-allowed MDC fields and the metric-cardinality tag set.

## Out of Scope

| Item | Reason |
| --- | --- |
| New metrics or trace spans | Owned by F-OBSERVABILITY (Done). This feature only logs. |
| Log aggregation / shipping infrastructure | F-AWS already routes stdout → CloudWatch via FireLens. |
| Per-customer log redaction / GDPR tooling | Out of project scope (no real customers). |
| Replacing the `logstash-logback-encoder` setup | AD-022 already frozen the encoder. |
| Migrating to `System.Logger` or another facade | SLF4J + Logback is the standard. |
| Changing log format from JSON | AD-019 already frozen. |

---

## User Stories

### P1: Bracketed request-path INFO logs ⭐ MVP

**User Story:** As an operator triaging a production incident from CloudWatch Logs alone, I
want every HTTP request to leave at least two log lines (enter + exit) so that I can scan a
log group and immediately see which requests succeeded, which failed, and how long they
took — without correlating against the metrics backend.

**Acceptance Criteria:**

1. **WHEN** `POST /api/orders/generate-invoice` is invoked **THEN** an INFO log line SHALL
   be emitted at controller entry with `orderId` and `itemCount` (no payload body).
2. **WHEN** the controller returns a success **THEN** an INFO log line SHALL be emitted with
   `invoiceId`, response status, and elapsed-ms.
3. **WHEN** the controller returns a failure (exception path) **THEN** `ApiExceptionHandler`
   SHALL emit a WARN (HTTP 4xx) or ERROR (HTTP 5xx) log line with the rejection `codigo`,
   the response status, and the exception class — no stack trace at WARN; full stack at
   ERROR.
4. **WHEN** any of the above lines is emitted **THEN** the JSON record SHALL already carry
   `correlationId`, `traceId`, `spanId` via MDC (F-OBSERVABILITY-provided).

**Independent Test:**

```bash
./mvnw test -Dtest='InvoiceControllerLoggingTest'
docker compose up -d
TOKEN=$(curl -sS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123"}' | jq -r .access_token)
curl -sS -X POST http://localhost:8080/api/orders/generate-invoice \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @src/main/resources/payloads/teste-pf.json > /dev/null
docker compose logs app | jq -c 'select(.message | test("invoice request"))' | head -5
```

The two-line bracketing pattern (`invoice request received` + `invoice request completed`)
must appear for the request.

---

### P1: Domain decision DEBUG logs ⭐ MVP

**User Story:** As an engineer debugging a tax-calculation report ("why did this order get
12 % instead of 15 %?"), I want a DEBUG line showing exactly which bracket the
`TaxRateTable` selected, with the order subtotal and the chosen rate — so that I do not
have to reason through the bracket code by hand.

**Acceptance Criteria:**

5. **WHEN** `TaxRateTable.findRate(order)` selects a bracket **THEN** a DEBUG log SHALL
   record `personType`, `taxRegime` (when JURIDICA), `totalItemsValue`, and the selected
   `rate`. **Excluded fields:** item-level detail, customer name.
6. **WHEN** `TaxRateTable.findRate(order)` rejects (`INVALID_PERSON_TYPE`,
   `INVALID_TAX_REGIME`, `UNSUPPORTED_TAX_REGIME`) **THEN** an INFO log SHALL record the
   `codigo` and the trigger (e.g., regime was `OUTROS`).
7. **WHEN** `LegacyFreightCalculator.calculateFreight(order)` resolves a delivery region
   **THEN** a DEBUG log SHALL record the resolved `region`, the base `freightValue`, and
   the applied multiplier.
8. **WHEN** `LegacyFreightCalculator` rejects (`INVALID_DELIVERY_REGION`) **THEN** an INFO
   log SHALL record the rejection `codigo`.
9. **WHEN** any of the above lines is emitted **THEN** `personType`, `taxRegime`, and
   `region` SHALL match the cardinality-bounded values from F-OBSERVABILITY
   §Cardinality Rules. `orderId` remains in MDC only — not in the message body.

**Independent Test:** assertion-based `ListAppender` tests (`TaxRateTableLoggingTest`,
`LegacyFreightCalculatorLoggingTest`) confirm the lines fire with the expected MDC.

---

### P1: Outbound adapter and Kafka dispatch logs ⭐ MVP

**User Story:** As an SRE chasing a `stockPort` 5xx alarm, I want the adapter to log its
enter/exit at DEBUG, success at DEBUG, and failure at WARN with the exception class — so
that I can grep the log group by `port=stockPort` and see exactly how each call landed,
not just the aggregated `invoice.dispatch{outcome=failure}` counter.

**Acceptance Criteria:**

10. **WHEN** any of `StockIntegrationAdapter`, `InvoiceRegistrationAdapter`,
    `DeliveryIntegrationAdapter`, `AccountsReceivableAdapter` is invoked **THEN** a DEBUG
    log SHALL fire at entry with the port name + `invoiceId`.
11. **WHEN** the adapter returns normally **THEN** a DEBUG log SHALL fire at exit with the
    port name, `invoiceId`, and elapsed-ms.
12. **WHEN** the adapter throws (`IntegrationAdapterException`, `InterruptedException`,
    Resilience4j-thrown `CallNotPermittedException` / `BulkheadFullException`) **THEN** a
    WARN log SHALL fire with the port name, `invoiceId`, exception class, and
    `exception.message` — no full stack at WARN level (the Logback JSON encoder serialises
    the cause chain at ERROR only).
13. **WHEN** `IntegrationEventPublisher.publish(...)` succeeds **THEN** the existing DEBUG
    line SHALL be preserved (no regression).
14. **WHEN** `IntegrationEventPublisher.publish(...)` fails **THEN** a WARN log SHALL fire
    with the `topic`, `eventId`, `invoiceId`, and exception class.

**Independent Test:** `IntegrationAdapterLoggingTest` invokes each adapter's happy + sad
paths through the existing `IntegrationAdapterException`-throwing harness and asserts the
log lines.

---

### P2: Resilience4j event logs

**User Story:** As an operator, I want the application to *announce* circuit-breaker state
transitions and bulkhead rejections in the log stream, so that an incident channel can
react to a "stockPort CB open" log line without having to subscribe to Prometheus alerts.

**Acceptance Criteria:**

15. **WHEN** a circuit breaker transitions to `OPEN` **THEN** a WARN log SHALL fire with
    `name` and `from` / `to` state.
16. **WHEN** a circuit breaker transitions to `HALF_OPEN` or back to `CLOSED` **THEN** an
    INFO log SHALL fire with the same fields.
17. **WHEN** a bulkhead rejects a permit (`BulkheadFullException`) **THEN** a WARN log
    SHALL fire with the bulkhead `name`. (The actual exception is logged by the adapter
    per AC-12; this is the bulkhead's own event-bus signal, useful when the rejection is
    captured by Resilience4j retry-topics rather than by the adapter.)
18. **WHEN** `RateLimitFilter` returns 429 **THEN** the existing DEBUG log line SHALL be
    promoted to INFO so that the trip surfaces in CloudWatch without DEBUG enabled.

---

### P2: Runtime log level toggling

**User Story:** As an on-call engineer in the middle of an incident, I want to be able to
crank `br.com.itau.invoicegenerator` from INFO to DEBUG at the container level via an env
var, so that I can grab one verbose request without a redeploy.

**Acceptance Criteria:**

19. **WHEN** the container starts with env var `APP_LOG_LEVEL=DEBUG` **THEN**
    `logback-spring.xml` SHALL apply DEBUG to logger `br.com.itau.invoicegenerator` and
    inherit the rest from root.
20. **WHEN** the env var is unset **THEN** the default level SHALL be INFO.
21. **WHEN** Spring Boot Actuator `/actuator/loggers` is reachable (it already is via
    F-OBSERVABILITY's management surface) **THEN** an operator MAY also use the actuator
    POST to flip levels on a running container without a restart — no extra code
    required, just a documentation pointer.

---

### P3: Documentation

**User Story:** As a future maintainer, I want a single section in `docs/observability.md`
that lists which loggers emit what, at which level, and how to read the JSON envelope.

**Acceptance Criteria:**

22. **WHEN** the feature is complete **THEN** `docs/observability.md` SHALL contain a
    "Debug logs catalog" subsection listing each logger, its message pattern, default
    level, and MDC fields it relies on.
23. **WHEN** the feature is complete **THEN** `CLAUDE.md` SHALL contain a one-line pointer
    under the Observability section noting that runtime log level is controlled by
    `APP_LOG_LEVEL` and `/actuator/loggers`.

---

## Edge Cases

- **WHEN** a log statement is invoked while MDC is empty (e.g., a Kafka consumer thread
  before `MdcRestoringRecordInterceptor` runs) **THEN** the line SHALL still emit — the
  MDC fields are simply absent from the JSON, never null-as-string.
- **WHEN** an exception payload is large **THEN** the JSON encoder SHALL still produce a
  single-line record (existing `logstash-logback-encoder` contract from F-OBSERVABILITY).
- **WHEN** a log statement is added to a hot path (`TaxRateTable.findRate` runs on every
  invoice) **THEN** the statement SHALL be DEBUG so production INFO logging does not
  amplify per-request log volume.
- **WHEN** the rate-limit INFO log is emitted in a flood (attacker scenario) **THEN** the
  per-IP `RateLimiter` bucket itself naturally caps the rate of log lines per IP — no
  separate log-rate guard needed.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| DLG-01 | P1: INFO log on controller entry with orderId + itemCount | Verified | Verified |
| DLG-02 | P1: INFO log on controller exit with invoiceId + status + elapsed-ms | Verified | Verified |
| DLG-03 | P1: WARN/ERROR log in ApiExceptionHandler with codigo + status | Verified | Verified |
| DLG-04 | P1: MDC carries correlationId/traceId/spanId on all log lines | Verified | Verified |
| DLG-05 | P1: DEBUG log on tax bracket selection (personType, regime, rate) | Verified | Verified |
| DLG-06 | P1: INFO log on TaxRateTable rejection with codigo | Verified | Verified |
| DLG-07 | P1: DEBUG log on freight calculation with region + multiplier | Verified | Verified |
| DLG-08 | P1: INFO log on LegacyFreightCalculator rejection with codigo | Verified | Verified |
| DLG-09 | P1: Domain logs respect cardinality budget; no orderId in message body | Verified | Verified |
| DLG-10 | P1: DEBUG enter log on each outbound adapter (4 adapters) | Verified | Verified |
| DLG-11 | P1: DEBUG exit-ok log with elapsed-ms on each outbound adapter | Verified | Verified |
| DLG-12 | P1: WARN log on adapter failure with exception class + message | Verified | Verified |
| DLG-13 | P1: Existing IntegrationEventPublisher DEBUG ok line preserved | Verified | Verified |
| DLG-14 | P1: WARN log on IntegrationEventPublisher failure | Verified | Verified |
| DLG-15 | P2: WARN log on CB transition to OPEN | Verified | Verified |
| DLG-16 | P2: INFO log on CB transition to HALF_OPEN / CLOSED | Verified | Verified |
| DLG-17 | P2: WARN log on bulkhead rejection event | Verified | Verified |
| DLG-18 | P2: Rate-limit 429 trip log promoted to INFO | Verified | Verified |
| DLG-19 | P2: `APP_LOG_LEVEL` env var toggles br.com.itau.invoicegenerator level | Verified | Verified |
| DLG-20 | P2: Default level INFO when env var unset | Verified | Verified |
| DLG-21 | P2: `/actuator/loggers` runtime override documented | Verified | Verified |
| DLG-22 | P3: docs/observability.md "Debug logs catalog" subsection | Verified | Verified |
| DLG-23 | P3: CLAUDE.md one-line pointer to APP_LOG_LEVEL | Verified | Verified |

**Status values:** Planned → In Tasks → Implementing → Verified

**Coverage:** 23 / 23 Verified. T1..T5 all shipped 2026-05-25.

---

## Dependencies and Sequencing

Hard dependencies (all already Done):

- **F-OBSERVABILITY** — provides MDC enrichment, `correlationId`/`traceId`/`spanId`,
  JSON encoder, and Spring profile bindings. This feature *uses* that infrastructure;
  it does not modify it.
- **F-RESILIENCE** — provides the `@CircuitBreaker` annotations whose event publishers
  Task T4 subscribes to.
- **F-BULKHEAD** — provides the `@Bulkhead` annotations whose event publishers Task T4
  subscribes to.
- **F-RATELIMIT** — provides the `RateLimitFilter` whose existing trip log Task T4
  promotes.

No soft dependencies. This feature is purely additive: removing every change still leaves
the application functional.

---

## Success Criteria

- [x] `./mvnw verify` passes (Spotless + Checkstyle + JaCoCo gate ≥ 85 % line / 75 %
      branch).
- [x] A single `POST /api/orders/generate-invoice` against the running container produces
      at least 8 JSON log lines (controller enter/exit, interactor enter/exit, tax bracket
      DEBUG, freight DEBUG, 4× adapter enter, 4× adapter exit, 4× Kafka publish ok). With
      `APP_LOG_LEVEL=INFO`, the volume drops to roughly 2 (controller enter/exit) — the
      DEBUG lines are gated.
- [x] A failed `POST /api/orders/generate-invoice` (`UNSUPPORTED_TAX_REGIME` /
      `INVALID_DELIVERY_REGION` / `INVALID_TAX_REGIME`) produces one INFO log on the
      domain rejection plus one WARN log on the HTTP 400 response.
- [x] A tripped circuit breaker emits a WARN log with the CB name and state transition.
- [x] No metric tag added by this feature (cardinality budget unchanged).
- [x] `docs/observability.md` has a "Debug logs catalog" subsection.
- [x] `CLAUDE.md` mentions `APP_LOG_LEVEL` once.
