# F-RESILIENCE Tasks

**Spec:** `.specs/features/resilience/spec.md`
**Status:** Done (2026-05-23). T1 + T2 landed in commits `<T1>` and `<T2>`.
**Granularity policy:** 2 consolidated vertical-slice tasks per [[feedback-task-granularity]].

---

## Execution Plan

```
T1 (resilience4j wire-up) ──→ T2 (docs + closure)
```

T1 is the working slice (deps, config, annotations, C-8 fix, tests). T2 closes the
feature with documentation updates.

---

## Task Breakdown

### T1: Resilience4j Spring Boot 3 + 4 circuit breakers + C-8 fix

**What:** Add the Resilience4j Spring Boot 3 starter, register one named circuit
breaker per outbound port (`stockPort`, `invoiceRegistrationPort`, `deliveryPort`,
`accountsReceivablePort`) with externalised per-port config, annotate each adapter
method with `@CircuitBreaker`, replace every `catch (InterruptedException) → new
RuntimeException` site with a typed `IntegrationAdapterException` that preserves the
interrupt flag, and add unit tests proving the OPEN/HALF_OPEN/CLOSED lifecycle for each
port.

**Where:**

- `pom.xml` — add `io.github.resilience4j:resilience4j-spring-boot3` and
  `resilience4j-micrometer` (managed via the Resilience4j BOM).
- `src/main/resources/application.properties` — four
  `resilience4j.circuitbreaker.instances.<name>.*` blocks.
- `src/main/java/.../adapter/integration/IntegrationAdapterException.java` — new,
  typed RuntimeException carrying the integration name + cause.
- `src/main/java/.../adapter/integration/stock/StockIntegrationAdapter.java` — add
  `@CircuitBreaker(name="stockPort")`, replace catch block.
- Same for `InvoiceRegistrationAdapter`, `DeliveryIntegrationAdapter`,
  `DeliverySchedulingClient`, `AccountsReceivableAdapter`.
- `src/test/java/.../adapter/integration/CircuitBreakerLifecycleTest.java` — one
  parameterised test or four small tests using a controlled fake port and the
  `CircuitBreakerRegistry` to drive OPEN/HALF_OPEN/CLOSED transitions.

**Depends on:** none.

**Reuses:** existing ports and adapter wiring; existing TestUseCases scaffolding; the
no-op fallback dispatcher in tests is unchanged.

**Requirements covered:** RES-01..RES-11.

**Done when:**

- [ ] `pom.xml` declares the Resilience4j starter at a version compatible with Spring
  Boot 3.5 (2.2.x at the time of writing). No explicit `<version>` if the Spring Boot
  BOM manages it; otherwise pin via the Resilience4j BOM.
- [ ] Each of the four ports has a `resilience4j.circuitbreaker.instances.<name>.*`
  block with `failure-rate-threshold`, `sliding-window-size`,
  `wait-duration-in-open-state`, `permitted-number-of-calls-in-half-open-state`, and
  `minimum-number-of-calls`. Defaults are sane for the demo (e.g.,
  `failure-rate-threshold=50`, `sliding-window-size=10`, `wait-duration=10s`).
- [ ] Each adapter method carries `@CircuitBreaker(name="<port>")`.
- [ ] Every `InterruptedException` catch block in the adapters
  (`StockIntegrationAdapter`, `InvoiceRegistrationAdapter`,
  `DeliveryIntegrationAdapter`, `DeliverySchedulingClient`,
  `AccountsReceivableAdapter`) calls `Thread.currentThread().interrupt()` and rethrows
  as `IntegrationAdapterException`.
- [ ] Unit test drives N consecutive failures on a fake `StockPort`, observes the CB
  transition to OPEN, asserts the next call throws `CallNotPermittedException`, waits
  the half-open transition, runs a probe success, and asserts CLOSED.
- [ ] Gate check passes: `./mvnw verify`
- [ ] Test count: 64 fast tests + N new (where N ≥ 1 for the lifecycle test) pass.

**Tests:** unit (Resilience4j `CircuitBreakerRegistry` + a controlled fake port).
**Gate:** full (`./mvnw verify`).

**Commit:** `feat(resilience): circuit breakers on outbound adapters; fix C-8 interrupt flag`

---

### T2: Documentation + feature closure

**What:** Update `ROADMAP.md`, `STATE.md` (new ADRs for the Resilience4j choice and the
deferred `@TimeLimiter`), `CONCERNS.md` (mark C-8 resolved), `CLAUDE.md` Defect Status
block, and the `tasks.md` Status flag in this folder.

**Where:**

- `.specs/project/ROADMAP.md` — F-RESILIENCE PLANNED → COMPLETE.
- `.specs/project/STATE.md` — AD-026 (Resilience4j Spring Boot 3 starter), AD-027
  (Defer `@TimeLimiter`), AD-028 (C-8 fix scope). Add quick-task entries.
- `.specs/codebase/CONCERNS.md` — C-8 marked resolved with reference to T1.
- `CLAUDE.md` — Defect Status section mentions the new breakers and C-8.
- `.specs/features/resilience/tasks.md` — Status: Draft → Done.

**Depends on:** T1.

**Reuses:** existing doc-update patterns from F-DEFECTS-PERFORMANCE T5.

**Requirements covered:** RES-12 (cross-link to F-OBSERVABILITY), plus the documentation
trail required by every feature closure.

**Done when:**

- [ ] ROADMAP marks F-RESILIENCE COMPLETE with date.
- [ ] STATE has three new ADRs explaining: which library, why no TimeLimiter, scope of
  C-8 fix.
- [ ] CONCERNS C-8 is resolved with regression coverage pointer.
- [ ] CLAUDE.md notes the new breakers in the Defect Status section.
- [ ] Gate check passes: `./mvnw verify`

**Tests:** none (docs only).
**Gate:** full.

**Commit:** `docs(resilience): close F-RESILIENCE; mark C-8 resolved; record ADRs`

---

## Task Granularity Check

| Task | Scope | Vertical-slice cohesion | Status |
| --- | --- | --- | --- |
| T1 | Deps + 4 CB configs + 4 annotations + C-8 fix + tests | Single "outbound adapters are protected end-to-end" slice. | ✅ |
| T2 | Doc closure across ROADMAP/STATE/CONCERNS/CLAUDE | Single "feature closure" slice. | ✅ |

## Test Co-location Validation

| Task | Code layer(s) modified | Matrix requires | Task says | Status |
| --- | --- | --- | --- | --- |
| T1 | adapter/integration/* + adapter/integration utility (CB lifecycle behavior) | unit (per TESTING.md) | unit | ✅ |
| T2 | docs only | none | none | ✅ |
