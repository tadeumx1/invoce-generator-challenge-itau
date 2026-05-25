# F-DEBUG-LOGS Tasks

**Spec:** `.specs/features/debug-logs/spec.md`
**Status:** Done (2026-05-25, `./mvnw verify` green; 143 fast tests including 2 new in
`DebugLogsIntegrationTest` + 2 new in `ResilienceEventLoggerTest`)
**Granularity policy:** 5 vertical slices, per user preference (cf.
`memory/feedback_task-granularity.md`). Each task is one atomic commit covering one
coherent slice of the request path, with co-located tests when assertions are needed.

---

## Execution Plan

```
T1 (HTTP path) ──┐
T2 (domain)    ──┼──→ T5 (tests + logback profile + docs)
T3 (adapters)  ──┤
T4 (resilience)──┘
```

- **T1..T4** are independent slices — they touch different packages with no shared file.
  They run sequentially (in ID order) to keep each commit reviewable rather than `[P]`.
- **T5** is the wrap-up: assertion-based `ListAppender` tests, `logback-spring.xml` env-var
  binding, `docs/observability.md` catalog subsection, `CLAUDE.md` pointer, final `./mvnw
  verify` gate.

Each task ends with a `./mvnw verify` gate (green) and one atomic commit using the
standard `feat(F-DEBUG-LOGS): ...` prefix.

---

## Task Breakdown

### T1: HTTP request-path bracketing — controller + interactor + exception handler

**What:** Add structured INFO logs at the controller boundary (enter + exit), inside
`GenerateInvoiceInteractor` (begin + end), and on every branch of `ApiExceptionHandler`.
After this task, every HTTP request leaves at least 2 INFO lines on success and 1 WARN
or ERROR on failure — independent of any other layer's logs.

**Where:**

- `src/main/java/br/com/itau/invoicegenerator/adapter/web/InvoiceController.java` —
  add `Logger LOG` field, INFO line on entry with `orderId`/`itemCount`, INFO line on
  exit with `invoiceId`/elapsed-ms. Capture `System.nanoTime()` to compute elapsed.
- `src/main/java/br/com/itau/invoicegenerator/application/GenerateInvoiceInteractor.java`
  — add `Logger log` field, INFO `invoice generation begin` and `invoice generation
  complete` lines.
- `src/main/java/br/com/itau/invoicegenerator/adapter/web/ApiExceptionHandler.java` —
  log every `@ExceptionHandler` branch: WARN on 400/401/403/429, ERROR on 500. Include
  the response `codigo`, the HTTP status, and the exception class.

**Depends on:** none (F-OBSERVABILITY MDC already wired).
**Reuses:** existing `CorrelationIdFilter` MDC bindings.

**Done when:**

- The three files import SLF4J `Logger` + `LoggerFactory`.
- A manual `curl` against a running app produces the bracketing INFO pair.
- `./mvnw verify` passes.

**Tests:** none in this task — assertion-based logging tests centralised in T5 so we
write one `ListAppender` harness rather than five.

**Gate:** `./mvnw verify`.

**Covers:** DLG-01, DLG-02, DLG-03, DLG-04.

---

### T2: Domain decision logs — TaxRateTable + LegacyFreightCalculator

**What:** Add DEBUG logs at the decision points in both domain services, plus INFO logs
on the `InvalidInvoiceOrderException` paths. Domain layer must stay clean of Spring /
Jackson per CLAUDE.md, so logging uses plain SLF4J (which is *not* Spring — already on
the classpath transitively through `spring-boot-starter`, allowed by AD-009 because
SLF4J is the JVM-wide logging facade).

**Where:**

- `src/main/java/br/com/itau/invoicegenerator/domain/service/TaxRateTable.java` —
  DEBUG line in `findRate(order)` after the bracket is resolved (one line per success
  branch). INFO line in each `invalid(code, msg)` call site BEFORE throwing.
- `src/main/java/br/com/itau/invoicegenerator/domain/service/LegacyFreightCalculator.java`
  — DEBUG line in `calculateFreight(order)` after the region is resolved, with the
  multiplier. INFO line on the `INVALID_DELIVERY_REGION` rejection path.

**Depends on:** T1 (no — independent; sequencing is review convenience only).
**Reuses:** existing exception classes; no new types.

**Done when:**

- Both files import SLF4J.
- `./mvnw verify` passes.

**Tests:** none in this task (centralised in T5).

**Gate:** `./mvnw verify`.

**Covers:** DLG-05, DLG-06, DLG-07, DLG-08, DLG-09.

---

### T3: Outbound integration adapter + Kafka publisher logs

**What:** Add DEBUG enter / DEBUG exit-ok / WARN exit-fail logs on the four outbound
integration adapters, and a WARN log on the `IntegrationEventPublisher` failure paths.
The existing DEBUG success log in `IntegrationEventPublisher` is preserved verbatim.

**Where:**

- `src/main/java/br/com/itau/invoicegenerator/adapter/integration/stock/StockIntegrationAdapter.java`
- `src/main/java/br/com/itau/invoicegenerator/adapter/integration/registration/InvoiceRegistrationAdapter.java`
- `src/main/java/br/com/itau/invoicegenerator/adapter/integration/delivery/DeliveryIntegrationAdapter.java`
- `src/main/java/br/com/itau/invoicegenerator/adapter/integration/finance/AccountsReceivableAdapter.java`
  — each: add SLF4J logger, capture `System.nanoTime()` at entry, log DEBUG entry,
  surround the `Thread.sleep(...)` in `try { ... } catch (InterruptedException e)`,
  log WARN in the catch with the port name + exception class. Log DEBUG exit-ok after
  sleep with elapsed-ms.
- `src/main/java/br/com/itau/invoicegenerator/adapter/messaging/IntegrationEventPublisher.java`
  — add WARN log in both `catch` blocks before throwing
  `IntegrationEventPublishException`. Keep the existing success DEBUG line.

**Depends on:** none (independent of T1/T2 in code, though sequenced for review).
**Reuses:** existing `IntegrationAdapterException` class.

**Done when:**

- Five files updated, no behavioural changes (no new exceptions, no swallowed errors).
- `./mvnw verify` passes.

**Tests:** none in this task (centralised in T5).

**Gate:** `./mvnw verify`.

**Covers:** DLG-10, DLG-11, DLG-12, DLG-13, DLG-14.

---

### T4: Resilience event logs — CB / bulkhead / rate-limit

**What:** Subscribe to Resilience4j's `CircuitBreaker.EventPublisher` and
`Bulkhead.EventPublisher` for each named instance and log transitions / rejections.
Promote the existing `RateLimitFilter` DEBUG trip line to INFO.

**Where:**

- New file:
  `src/main/java/br/com/itau/invoicegenerator/adapter/observability/ResilienceEventLogger.java`
  — Spring `@Component` that takes `CircuitBreakerRegistry` + `BulkheadRegistry` in its
  constructor, iterates each registry, registers an event listener that logs state
  transitions (WARN on OPEN, INFO on HALF_OPEN / CLOSED) and bulkhead rejections (WARN).
  Use `@PostConstruct` to attach listeners after Spring builds the registries.
- `src/main/java/br/com/itau/invoicegenerator/adapter/security/ratelimit/RateLimitFilter.java`
  — flip the existing trip line from `LOG.debug(...)` to `LOG.info(...)` and enrich it
  with `path` + `method`.

**Depends on:** none.
**Reuses:** `CircuitBreakerRegistry` + `BulkheadRegistry` (both auto-configured by the
`resilience4j-spring-boot3` starter, no extra wiring).

**Done when:**

- A new bean `ResilienceEventLogger` is wired and emits WARN when a CB transitions to
  OPEN (verified in T5 by forcing the threshold).
- `RateLimitFilter` trip log is at INFO.
- `./mvnw verify` passes.

**Tests:** none in this task (a forced-trip test lives in T5).

**Gate:** `./mvnw verify`.

**Covers:** DLG-15, DLG-16, DLG-17, DLG-18.

---

### T5: Tests, logback env-var binding, docs

**What:** Wrap up — assertion-based logging tests, runtime log-level toggling via
`APP_LOG_LEVEL`, and the operator-facing doc updates.

**Where:**

- New test:
  `src/test/java/br/com/itau/invoicegenerator/observability/DebugLogsIntegrationTest.java`
  — uses Logback `ListAppender` attached to logger `br.com.itau.invoicegenerator`,
  exercises the controller via `MockMvc` against the existing test slice, asserts:
  1. controller emits the bracketing pair (DLG-01 + DLG-02),
  2. `TaxRateTable` emits the bracket-selection DEBUG (DLG-05),
  3. `LegacyFreightCalculator` emits the region DEBUG (DLG-07),
  4. a `JURIDICA + OUTROS` request produces the INFO rejection (DLG-06) followed by
     `ApiExceptionHandler`'s WARN (DLG-03),
  5. MDC fields `correlationId`, `traceId`, `spanId` are present on the captured records
     (DLG-04 + DLG-09).
- New test:
  `src/test/java/br/com/itau/invoicegenerator/observability/ResilienceEventLoggerTest.java`
  — wires a real `CircuitBreaker` with a 1-call-threshold, forces an exception, asserts
  the WARN-on-OPEN line (DLG-15) and the subsequent INFO-on-CLOSED after wait + success.
- `src/main/resources/logback-spring.xml` — add a `<property name="APP_LOG_LEVEL"
  source="APP_LOG_LEVEL" defaultValue="INFO" />` binding and apply it to the
  `<logger name="br.com.itau.invoicegenerator" level="${APP_LOG_LEVEL}" />` entry.
- `docs/observability.md` — add a `### Debug logs catalog` subsection listing each logger,
  its emitted message pattern, the default level, and the MDC fields it depends on.
- `CLAUDE.md` — add one line under the Observability section: "Runtime log level is
  controlled by env var `APP_LOG_LEVEL` (default INFO); `/actuator/loggers` POST also
  works without a restart."
- `.specs/features/debug-logs/spec.md` — flip every DLG-* status to Verified.
- `.specs/project/STATE.md` — add **AD-037** recording the feature decision (logging
  the request path on top of F-OBSERVABILITY MDC, no new metrics, env-var toggle).
- `.specs/project/ROADMAP.md` — add a row under the appropriate milestone marking
  F-DEBUG-LOGS complete.

**Depends on:** T1, T2, T3, T4 (asserts the log lines they ship).

**Done when:**

- Both new tests pass.
- `APP_LOG_LEVEL=DEBUG docker compose up` shows DEBUG lines; default `docker compose up`
  shows only INFO and above.
- `./mvnw verify` passes — JaCoCo gate unchanged.
- `docs/observability.md` + `CLAUDE.md` updated.

**Tests:** the two new test classes above. Existing 137-test suite remains green; new
tests bring the suite to ≥ 139.

**Gate:** `./mvnw verify`, plus a manual `curl` smoke against a running app to confirm
the bracketing pair appears in `docker compose logs app | jq -c .`.

**Covers:** DLG-19, DLG-20, DLG-21, DLG-22, DLG-23, plus end-to-end verification of
DLG-01..DLG-18.

---

## Traceability summary

| Task | Covers DLG-* | New files | Modified files |
| --- | --- | --- | --- |
| T1 | 01, 02, 03, 04 | 0 | 3 |
| T2 | 05, 06, 07, 08, 09 | 0 | 2 |
| T3 | 10, 11, 12, 13, 14 | 0 | 5 |
| T4 | 15, 16, 17, 18 | 1 | 1 |
| T5 | 19..23 + verification of 01..18 | 2 | 4 |
| **Total** | **23 / 23** | **3** | **15** |
