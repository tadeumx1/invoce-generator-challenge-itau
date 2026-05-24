# F-BULKHEAD — Tasks

**Spec:** [spec.md](spec.md) — 18 requirement IDs (BH-01..BH-18).
**Task granularity:** 3 vertical-slice tasks, per task-granularity feedback memory.
**Gate:** `./mvnw verify` after each task — must stay green.

---

## T1 — Annotate the four adapters + configure `application.properties`

**What:** Add `@Bulkhead(name = CIRCUIT_BREAKER_NAME)` to each outbound adapter method that
already carries `@CircuitBreaker`. Add the four `resilience4j.bulkhead.instances.*` blocks to
`application.properties` with values `20 / 20 / 5 / 20` and `max-wait-duration=0` everywhere.

**Where:**
- `src/main/java/.../adapter/integration/stock/StockIntegrationAdapter.java`
- `src/main/java/.../adapter/integration/registration/InvoiceRegistrationAdapter.java`
- `src/main/java/.../adapter/integration/delivery/DeliveryIntegrationAdapter.java`
- `src/main/java/.../adapter/integration/finance/AccountsReceivableAdapter.java`
- `src/main/resources/application.properties`

**Depends on:** —

**Reuses:**
- The existing `CIRCUIT_BREAKER_NAME` public constant on each adapter as the bulkhead name
  too. Renaming for symmetry (e.g., `RESILIENCE_NAME`) is out of scope; the constant value
  is what matters, not its identifier name.
- The existing `resilience4j.circuitbreaker.instances.*` property block as the structural
  template for the new bulkhead block.

**Done when:**
- BH-01, BH-02, BH-03, BH-04 — each adapter method carries `@Bulkhead(name = ...)`.
- BH-05, BH-06, BH-07 — four bulkhead instances declared in `application.properties` with
  the correct values; integers come from properties, not from code.
- `./mvnw verify` is green; no test regression.

**Tests:**
- The existing 103 fast tests must continue to pass. T1 by itself adds no new test —
  enforcement proof lands in T2.

**Gate:** `./mvnw verify`.

---

## T2 — Bulkhead enforcement test (registered ≠ exercised)

**What:** Write a focused integration test that **saturates the `deliveryPort` bulkhead** and
asserts the next call is rejected with `BulkheadFullException` (or its Spring AOP wrapper).
After releasing one permit, the next call must proceed. The test must use a small
`max-concurrent-calls` (3 is enough) so the saturation latch logic stays simple.

**Where:**
- `src/test/java/.../adapter/integration/BulkheadEnforcementTest.java` (new test class).
  Alternative: extend `CircuitBreakerLifecycleTest` if it already provides the
  small-window-size test scaffolding — choose whichever yields the simpler diff.

**Depends on:** T1.

**Reuses:**
- The `CircuitBreakerLifecycleTest` pattern for a small Spring context + a stubbed downstream
  client that the test controls via latches. The bulkhead test follows the same shape.
- The Resilience4j `BulkheadRegistry` bean autowired by `resilience4j-spring-boot3`, to
  introspect permit counts before/after the saturation.
- `@TestPropertySource(properties = "resilience4j.bulkhead.instances.deliveryPort.max-concurrent-calls=3")`
  so the production value (5) does not leak into the test assertion.

**Done when:**
- BH-08 — 4th concurrent call to `deliveryPort` (with `max-concurrent-calls=3` test override)
  throws `BulkheadFullException` / `RequestNotPermitted` / appropriate wrapper.
- BH-09 — releasing one held permit lets the next call proceed.
- BH-10 — the test exercises the real `@Bulkhead`-annotated method via the production Spring
  context (no test-only `Bulkhead` bean replacement).
- BH-11 — the test override is via `@TestPropertySource`, not by mutating production
  `application.properties`.
- The cardinality guard `CardinalityGuardTest` from F-OBSERVABILITY remains green (no new
  forbidden tags introduced).
- BH-12, BH-13, BH-14 — a Prometheus scrape from a `MetricsIntegrationTest`-style helper
  (or the existing test, extended) returns the four bulkhead meter names. **No new collector
  code.** If the existing scrape test already iterates over registered meters, just add an
  assertion for the bulkhead binding presence.

**Tests:**
- New file: `BulkheadEnforcementTest` with ~3 test methods (saturate, release, scrape).
- Existing tests must stay green.

**Gate:** `./mvnw verify`.

---

## T3 — Documentation closure + ROADMAP/STATE/CLAUDE wiring

**What:** Wire the existing `docs/bulkhead-strategy.md` into `CLAUDE.md` and `README.md`,
record AD-033 in `STATE.md`, introduce M6 in `ROADMAP.md` (with F-BULKHEAD + F-API-DOCS as
the two features), and flip F-BULKHEAD to COMPLETE.

**Where:**
- `CLAUDE.md` — new "Bulkhead" subsection under the F-RESILIENCE / F-RATELIMIT block.
- `README.md` — hardening table or resilience section gains a row linking to
  `docs/bulkhead-strategy.md`.
- `.specs/project/STATE.md` — new `AD-033` entry following the AD-026 / AD-027 / AD-032
  template (Decision / Reason / Trade-off / Impact).
- `.specs/project/ROADMAP.md` — new `## M6 — Concurrency back-pressure + DX polish` section
  with F-BULKHEAD (status COMPLETE) and F-API-DOCS (status will be flipped by its own T3).

**Depends on:** T1, T2.

**Reuses:**
- The AD-026 entry as the template for AD-033 — same Decision/Reason/Trade-off/Impact shape.
- The M3 / M4 milestone format in ROADMAP.md as the template for M6.
- The existing `docs/bulkhead-strategy.md` link style used by `docs/observability.md`
  references in `CLAUDE.md`.

**Done when:**
- BH-15 — `CLAUDE.md` and `README.md` link to `docs/bulkhead-strategy.md`.
- BH-16 — `STATE.md` carries `AD-033` with the four scope decisions.
- BH-17 — `ROADMAP.md` shows M6 + F-BULKHEAD with status COMPLETE (date 2026-05-24).
- BH-18 — no new entry in Active/Resolved Blockers.
- All requirement statuses in `spec.md` flip from `Pending` → `Verified`.

**Tests:** None (documentation-only changes).

**Gate:** `./mvnw verify` (one final run after the entire feature lands).

---

## Coverage

| Task | Requirement IDs covered |
| --- | --- |
| T1 | BH-01, BH-02, BH-03, BH-04, BH-05, BH-06, BH-07 |
| T2 | BH-08, BH-09, BH-10, BH-11, BH-12, BH-13, BH-14 |
| T3 | BH-15, BH-16, BH-17, BH-18 |

**Total:** 18 / 18 mapped. 0 unmapped.
