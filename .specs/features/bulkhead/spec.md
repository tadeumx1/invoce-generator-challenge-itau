# F-BULKHEAD — Semaphore bulkhead on the four outbound integration adapters

**Status:** Draft (2026-05-24)
**Milestone:** M6 — Concurrency back-pressure + DX polish (new)
**Owner:** project owner
**Companion feature:** [F-API-DOCS](../api-docs/spec.md) (same milestone)
**Operator-facing doc:** [`docs/bulkhead-strategy.md`](../../../docs/bulkhead-strategy.md)

## Scope decision (user, 2026-05-24)

- **Library:** `io.github.resilience4j:resilience4j-spring-boot3` — already on the classpath
  via AD-026 (F-RESILIENCE circuit breakers). The bulkhead module ships in the same starter;
  **no new top-level dependency** is added.
- **Variant:** `SEMAPHORE` (default). Rejected: `THREADPOOL` would force `CompletableFuture<T>`
  return types on every adapter and propagate through ports/use case — same trade-off AD-027
  rejected for `@TimeLimiter`.
- **Coverage:** all four outbound adapters that already carry `@CircuitBreaker` —
  `StockIntegrationAdapter`, `InvoiceRegistrationAdapter`, `DeliveryIntegrationAdapter`,
  `AccountsReceivableAdapter`. **One bulkhead per adapter** (not a shared global one), so a
  slow adapter cannot starve permits a fast adapter could have used.
- **Calibration (option A):** `deliveryPort.max-concurrent-calls=5`,
  the other three at `20`. Rationale frozen in `docs/bulkhead-strategy.md` and AD-033.
- **Posture:** `max-wait-duration=0` — fail-fast on permit exhaustion. Failed calls bubble to
  the existing `@RetryableTopic` retry path; **the consumer thread is never blocked waiting
  for a permit**.
- **Configuration seam:** `resilience4j.bulkhead.instances.<name>.*` in
  `application.properties`, same shape as the existing circuit-breaker block (AD-026). Integer
  values are tunable per environment without code changes.
- **Naming:** the four bulkhead instance names match the existing circuit-breaker names
  byte-for-byte — `stockPort`, `invoiceRegistrationPort`, `deliveryPort`,
  `accountsReceivablePort`. Each adapter's `CIRCUIT_BREAKER_NAME` constant doubles as the
  bulkhead name to keep the configuration aligned per adapter.

## Problem statement

After F-RESILIENCE, the four outbound adapters are protected against **failures** (circuit
breaker reacts to a 50% failure-rate threshold). They have no protection against
**concurrency**: nothing caps the number of in-flight calls to a downstream that is healthy
but already busy.

Today this is dormant — the Spring Kafka listener concurrency is `1` per consumer
(`KafkaMessagingConfig` does not customise it), so steady-state in-flight calls per adapter is
1. But two foreseeable changes turn dormancy into risk:

1. **Listener concurrency bump.** Raising `concurrency=N` on `DeliverySchedulingConsumer` (or
   any of the other three) to drain a backlog faster instantly authorises N parallel
   downstream calls. The downstream may not absorb that load — especially `DeliveryPort`,
   whose `Thread.sleep(5000)` represents a slow real service.
2. **Loop / bug.** A runaway producer or a retry storm could fan out hundreds of parallel
   calls on a single adapter. The circuit breaker would only react after the first 5 of those
   calls failed; until then, the downstream is being hammered.

The bulkhead pattern is the **missing pre-failure guardrail**: it rejects excess concurrent
calls before the downstream even sees them, regardless of whether they would have succeeded
or failed. Combined with the existing circuit breaker (post-failure reaction) and the
F-RATELIMIT edge rate limiter (per-IP HTTP request rate), this closes the three independent
back-pressure axes:

| Pattern | Axis | Where it lives |
| --- | --- | --- |
| Rate limiter | request **rate** at the HTTP boundary | Servlet filter (F-RATELIMIT) |
| **Bulkhead** | **outbound concurrency** | **Adapter methods (this spec)** |
| Circuit breaker | outbound **failure** rate | Adapter methods (F-RESILIENCE) |

## Goals

- [ ] **BH-G1:** Each of the four outbound adapters carries a `@Bulkhead(name=<port>)`
      annotation alongside its existing `@CircuitBreaker(name=<port>)`. The bulkhead name
      equals the existing `CIRCUIT_BREAKER_NAME` constant per adapter.
- [ ] **BH-G2:** `application.properties` declares four bulkhead instances under
      `resilience4j.bulkhead.instances.*.max-concurrent-calls` with values `20 / 20 / 5 / 20`
      and `max-wait-duration=0` on all four.
- [ ] **BH-G3:** The four bulkhead Micrometer meters
      (`resilience4j.bulkhead.available.concurrent.calls{name}` and
      `resilience4j.bulkhead.max.allowed.concurrent.calls{name}`) appear on
      `GET /actuator/prometheus` after at least one request exercises each adapter.
- [ ] **BH-G4:** A focused integration test proves the bulkhead is **wired and enforced**
      (mirrors AD-029: registered ≠ exercised) by saturating the `deliveryPort` permits and
      asserting the next call is rejected with `BulkheadFullException`.
- [ ] **BH-G5:** All existing 103+ fast tests continue to pass. `./mvnw verify` is green.
- [ ] **BH-G6:** `docs/bulkhead-strategy.md` (already written) is linked from `CLAUDE.md` and
      `README.md`; `STATE.md` records `AD-033` capturing the four scope decisions; `ROADMAP.md`
      introduces M6 with F-BULKHEAD and flips it to COMPLETE on landing.

## Out of scope

| Item | Reason | Tracker |
| --- | --- | --- |
| `THREADPOOL` bulkhead variant | Forces `CompletableFuture<T>` on every adapter — same trade-off as AD-027. | BH-OOS-1 |
| Single global bulkhead across all four adapters | A slow adapter would starve fast adapters. Defeats the "watertight compartments" principle. | BH-OOS-2 |
| Bulkhead on the Kafka producer side (`KafkaTemplate.send`) | Producer has its own `max.in.flight.requests.per.connection=5` (already in `application.properties`). Duplicating with a bulkhead would be cinto+suspensório. | BH-OOS-3 |
| Bulkhead on the use case (`GenerateInvoiceInteractor`) | The use case is fast (in-memory tax calc + 4 Kafka publishes). No measurable downstream to protect. | BH-OOS-4 |
| Adaptive bulkhead (raise/lower based on load) | Same out-of-scope as F-RATELIMIT's RLIM-OOS-8. Captured for future. | BH-OOS-5 |
| Promoting bulkhead signals to an SLI | AD-017 freezes the SLI catalog at four. The new meters remain queryable; no dashboard/alarm promotion. | BH-OOS-6 |
| Tuning the integer values per environment profile | Defaults serve all profiles today. Per-profile overrides happen if/when real environments diverge. | BH-OOS-7 |
| Bulkhead on `/api/auth/login` BCrypt verification | F-RATELIMIT already throttles that surface per-IP. A bulkhead would be redundant under a low IP-rate. | BH-OOS-8 |

## User stories

### P1: Each adapter has its own bulkhead with calibrated limits ⭐ MVP

**User Story:** As an SRE, I want each outbound adapter to enforce an independent
`max-concurrent-calls` ceiling, so that a future bump in consumer concurrency cannot fan out
unbounded parallel calls to a slow downstream.

**Why P1:** Without per-adapter bulkheads, the circuit breaker is the only protection — and it
only reacts *after* failures. A healthy-but-saturated downstream gets no protection at all.

**Acceptance criteria:**

- [ ] **BH-01:** `StockIntegrationAdapter.sendInvoiceForStockDeduction(...)` carries
      `@Bulkhead(name = "stockPort")` annotation alongside the existing `@CircuitBreaker`.
- [ ] **BH-02:** `InvoiceRegistrationAdapter.registerInvoice(...)` carries
      `@Bulkhead(name = "invoiceRegistrationPort")`.
- [ ] **BH-03:** `DeliveryIntegrationAdapter.scheduleDelivery(...)` carries
      `@Bulkhead(name = "deliveryPort")`.
- [ ] **BH-04:** `AccountsReceivableAdapter.sendInvoiceToAccountsReceivable(...)` carries
      `@Bulkhead(name = "accountsReceivablePort")`.
- [ ] **BH-05:** `application.properties` declares:

      ```properties
      resilience4j.bulkhead.instances.stockPort.max-concurrent-calls=20
      resilience4j.bulkhead.instances.stockPort.max-wait-duration=0

      resilience4j.bulkhead.instances.invoiceRegistrationPort.max-concurrent-calls=20
      resilience4j.bulkhead.instances.invoiceRegistrationPort.max-wait-duration=0

      resilience4j.bulkhead.instances.deliveryPort.max-concurrent-calls=5
      resilience4j.bulkhead.instances.deliveryPort.max-wait-duration=0

      resilience4j.bulkhead.instances.accountsReceivablePort.max-concurrent-calls=20
      resilience4j.bulkhead.instances.accountsReceivablePort.max-wait-duration=0
      ```

- [ ] **BH-06:** `deliveryPort` is **the only adapter** with `max-concurrent-calls < 10`. The
      asymmetry (5 vs 20) is intentional and documented in `docs/bulkhead-strategy.md` §4-5.
- [ ] **BH-07:** Integer values are **properties, not literals in code**. SREs can change them
      via `application-<profile>.properties` or environment variables without recompiling.

### P1: Bulkhead is wired and enforced (registered ≠ exercised)

**User Story:** As a future maintainer, I want a test that **fails if a future refactor
removes the bulkhead annotation** (or wires it to a wrong instance name), so that the AD-029
"registered but not exercised" failure mode is closed.

**Why P1:** F-OBSERVABILITY's AD-029 audit taught the project that a Spring bean can compile,
unit-test green, and still be silently unused in production. An integration test that actually
saturates the bulkhead is the only honest proof it is wired.

**Acceptance criteria:**

- [ ] **BH-08:** A new test (`BulkheadEnforcementTest` or addition to
      `CircuitBreakerLifecycleTest`) saturates the **`deliveryPort` bulkhead** by holding 5
      concurrent calls (latch-controlled stubs) and asserts the **6th call throws
      `BulkheadFullException`** (or its wrapper).
- [ ] **BH-09:** The same test asserts that **after releasing one held permit**, the next call
      proceeds (proving the permit is returned, not leaked).
- [ ] **BH-10:** The test runs against the **real Spring context** with the production
      `@Bulkhead` annotation — no test-only `Bulkhead` bean replacement, same posture as
      `JwtTestSupport` (AD-032) and `CircuitBreakerLifecycleTest`.
- [ ] **BH-11:** The test uses a small `max-concurrent-calls` (3 or 5) via
      `@TestPropertySource` if needed, so the saturation latch logic stays simple. The
      production properties are not depended upon by the test's numeric assertions.

### P2: Auto-exposed metrics for observability

**User Story:** As an SRE, I want the bulkhead state to show up on the Prometheus scrape
without any extra collector code, so that I can build an ad-hoc panel during an incident.

**Why P2:** `resilience4j-micrometer` (already on the classpath via AD-026) publishes the
bulkhead binding for free when an instance exists. No new code is needed; this AC simply
asserts the binding **actually works** end-to-end.

**Acceptance criteria:**

- [ ] **BH-12:** `GET /actuator/prometheus` after at least one request exercising each
      adapter returns lines containing:
      - `resilience4j_bulkhead_available_concurrent_calls{name="stockPort",...}`
      - `resilience4j_bulkhead_available_concurrent_calls{name="invoiceRegistrationPort",...}`
      - `resilience4j_bulkhead_available_concurrent_calls{name="deliveryPort",...}`
      - `resilience4j_bulkhead_available_concurrent_calls{name="accountsReceivablePort",...}`
      - `resilience4j_bulkhead_max_allowed_concurrent_calls{name="..."}` for each of the four.
- [ ] **BH-13:** **No `orderId` / `invoiceId` / `correlationId` ever lands as a bulkhead
      metric tag** — same cardinality guard as AD-020. The `name` tag is bounded to the
      four instance names.
- [ ] **BH-14:** No new Micrometer collector code is added — the binding is verified by
      reading the existing scrape, not by writing it.

### P3: Documentation closure

**User Story:** As a future reviewer, I want the bulkhead rationale, calibration, and
operational meaning recorded once in a discoverable place, so that nobody re-opens the same
trade-off conversation.

**Acceptance criteria:**

- [ ] **BH-15:** `docs/bulkhead-strategy.md` exists and is linked from:
      - `CLAUDE.md` under a new "Bulkhead" subsection next to the existing F-RESILIENCE
        notes.
      - `README.md` resilience section / hardening table.
- [ ] **BH-16:** `STATE.md` records **AD-033** with the four scope decisions (semaphore over
      threadpool, per-adapter not global, option-A calibration, fail-fast posture).
- [ ] **BH-17:** `ROADMAP.md` introduces **M6** with F-BULKHEAD and F-API-DOCS as its two
      features and flips F-BULKHEAD to COMPLETE on landing.
- [ ] **BH-18:** F-BULKHEAD adds **no new entry to the "Active Blockers" or "Resolved
      Blockers"** list — it surfaces no incident; it is forward-looking insurance.

## Edge cases

- WHEN a call is in flight on `deliveryPort` AND a 6th concurrent call arrives within the same
  millisecond, THEN exactly one call SHALL be rejected (Resilience4j's semaphore is
  thread-safe and provides this guarantee). The bulkhead is not subject to the same races
  the rate-limiter's fixed window can exhibit.
- WHEN the bulkhead rejects a call (`BulkheadFullException`), THEN the consumer's
  `@RetryableTopic` machinery SHALL treat it as a retryable failure — same exception-handling
  contract as a transient downstream error. **The HTTP request does not see the rejection**
  because by the time the consumer runs, the synchronous HTTP path has already returned 2xx
  (per AD-016).
- WHEN a bulkhead instance is referenced in code but missing from `application.properties`,
  THEN Resilience4j SHALL fall back to library defaults (`max-concurrent-calls=25`,
  `max-wait-duration=0`). The startup log SHALL show the default being applied — operators
  must catch missing config from logs, not from a hard failure (consistent with the
  circuit-breaker behaviour from AD-026).
- WHEN `Thread.currentThread().interrupt()` runs inside an adapter (the existing C-8 interrupt
  fix from F-RESILIENCE) AND the call had taken a bulkhead permit, THEN the permit SHALL be
  released on method exit. Resilience4j's annotation processor wraps in try/finally
  semantics — no permit leak is possible from a thrown `IntegrationAdapterException`.
- WHEN a real HTTP downstream replaces the `Thread.sleep` stub in a future feature AND that
  downstream documents a different concurrency SLA (e.g., "10 concurrent calls max"), THEN
  the only change required is `application.properties` — no code change. This is the
  designed-for evolution path.

## Requirement traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| BH-01 | P1: Annotated adapters | Tasks | Pending |
| BH-02 | P1: Annotated adapters | Tasks | Pending |
| BH-03 | P1: Annotated adapters | Tasks | Pending |
| BH-04 | P1: Annotated adapters | Tasks | Pending |
| BH-05 | P1: Annotated adapters | Tasks | Pending |
| BH-06 | P1: Annotated adapters | Tasks | Pending |
| BH-07 | P1: Annotated adapters | Tasks | Pending |
| BH-08 | P1: Enforcement test | Tasks | Pending |
| BH-09 | P1: Enforcement test | Tasks | Pending |
| BH-10 | P1: Enforcement test | Tasks | Pending |
| BH-11 | P1: Enforcement test | Tasks | Pending |
| BH-12 | P2: Auto-exposed metrics | Tasks | Pending |
| BH-13 | P2: Auto-exposed metrics | Tasks | Pending |
| BH-14 | P2: Auto-exposed metrics | Tasks | Pending |
| BH-15 | P3: Documentation closure | Tasks | Pending |
| BH-16 | P3: Documentation closure | Tasks | Pending |
| BH-17 | P3: Documentation closure | Tasks | Pending |
| BH-18 | P3: Documentation closure | Tasks | Pending |

**ID format:** `BH-NN`.
**Status values:** Pending → In Tasks → Implementing → Verified.
**Coverage:** 18 total — mapped to 3 vertical-slice tasks in `tasks.md` (per task-granularity feedback).

## Success criteria

F-BULKHEAD is COMPLETE when:

1. ✅ `./mvnw verify` is green; total fast test count grows by ≥1 (BH-08 enforcement test).
2. ✅ `grep -c "@Bulkhead" src/main/java/.../adapter/integration/**/*.java` returns 4.
3. ✅ `grep -c "resilience4j.bulkhead.instances" src/main/resources/application.properties`
   returns ≥4 (one line per instance).
4. ✅ `docs/bulkhead-strategy.md` exists, linked from `CLAUDE.md` + `README.md`.
5. ✅ `STATE.md` records AD-033; `ROADMAP.md` shows F-BULKHEAD under M6 status COMPLETE.
6. ✅ A local-stack `curl + Prometheus scrape` returns the four bulkhead meters.
