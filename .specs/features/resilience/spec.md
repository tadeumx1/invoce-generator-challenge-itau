# F-RESILIENCE — Circuit Breaker on Outbound Adapters Specification

## Problem Statement

After F-DEFECTS-PERFORMANCE the four downstream integrations (stock deduction, invoice
registration, delivery scheduling, accounts receivable) are invoked from Kafka consumers,
not from the HTTP request thread. Spring Kafka's `@RetryableTopic` already redelivers
transient failures up to four attempts and routes exhausted failures to a DLT.

What is still missing is **bounded protection per downstream**. If `StockIntegrationAdapter`
starts consistently failing (every call throws), the consumer thread is still made to
call it 4 times per message (one initial + 3 retries) and a flood of failing invoices
keeps hammering the broken downstream. The consumer pool can saturate, retry topics back
up, and the DLT becomes the only fast path.

The fix is a **circuit breaker per outbound port adapter** using Resilience4j. When a
downstream is unhealthy the circuit opens, calls fail fast, the consumer drops the
message into the next retry topic immediately, and the unhealthy downstream gets a
breather instead of more traffic.

While the adapters are being touched, the long-standing **C-8** concern (every
`Thread.sleep` catch block discards the interrupt flag) gets fixed: `Thread.currentThread().interrupt()` before rethrowing.

## Scope and Non-Scope

| In scope | Out of scope |
| --- | --- |
| `@CircuitBreaker` on `sendInvoiceForStockDeduction`, `registerInvoice`, `scheduleDelivery`, `sendInvoiceToAccountsReceivable`. | TimeLimiter / per-call timeout. Requires async return types; deferred. Documented in `STATE.md`. |
| Per-port config (`resilience4j.circuitbreaker.instances.<name>.*`) in `application.properties`. | Bulkhead. Spring Kafka listener concurrency already bounds parallelism. |
| Resilience4j Micrometer bindings on the classpath (consumed by F-OBSERVABILITY). | Dashboards / alerts on the metrics. Owned by F-OBSERVABILITY / F-AWS. |
| C-8 fix (`Thread.currentThread().interrupt()` + typed exception) on every adapter sleep site. | Replacing `Thread.sleep` with a non-blocking implementation — the sleeps are the simulation. |
| Unit tests proving the circuit opens after a configured number of failures. | EmbeddedKafka-level test of retry-topic flow under circuit-open state — covered indirectly by the existing F-DEFECTS-PERFORMANCE flow test. |

## Messaging Decision

Resilience4j is the de-facto Spring Boot 3.x choice for circuit-breaker / retry /
time-limiter. We pin `io.github.resilience4j:resilience4j-spring-boot3` (managed by the
Resilience4j BOM at the latest 2.x line compatible with Spring Boot 3.5).

`@CircuitBreaker` is the only Resilience4j annotation adopted in T1. `@TimeLimiter` is
deferred because it forces a `CompletableFuture` return type on every adapter method,
which would ripple to ports and consumers without measurable upside for this challenge:

- The downstream "slowness" is a `Thread.sleep` simulation. It does not hang indefinitely;
  the maximum is 5 seconds (delivery + 5-item trap).
- Kafka consumer thread is one per partition by default; a 5-second sleep on the consumer
  thread is acceptable for the demo.
- A production deployment with real HTTP downstream calls **must** add `@TimeLimiter`.

---

## User Stories

### P1: Circuit-broken downstream stops absorbing traffic ⭐ MVP

**User Story:** As an SRE, I want a failing downstream to be circuit-broken at the
adapter boundary, so that the system stops hammering it and the consumer thread is freed
to handle other work.

**Why P1:** Without circuit breakers, every failing call still pays the full wait time
(retry topic backoff + adapter sleep) for every redelivery — even when the downstream is
known to be down. The Kafka retry topic backs up unnecessarily.

**Acceptance Criteria:**

1. **WHEN** the application starts **THEN** Resilience4j SHALL register four named
   circuit breakers: `stockPort`, `invoiceRegistrationPort`, `deliveryPort`, and
   `accountsReceivablePort`.
2. **WHEN** an adapter method throws **THEN** the matching circuit breaker SHALL record
   a failure call.
3. **WHEN** the configured failure-rate threshold is crossed inside the sliding window
   **THEN** the circuit SHALL transition to OPEN.
4. **WHEN** the circuit is OPEN **THEN** the next call SHALL throw
   `CallNotPermittedException` without invoking the adapter body (no `Thread.sleep`,
   no I/O).
5. **WHEN** the wait-duration-in-open-state elapses **THEN** the circuit SHALL
   transition to HALF_OPEN and permit a bounded number of probe calls.
6. **WHEN** a probe call succeeds while HALF_OPEN **THEN** the circuit SHALL transition
   back to CLOSED.

**Independent Test:**

```bash
./mvnw test -Dtest='*CircuitBreaker*Test'
```

### P1: Per-adapter SLA is externalised ⭐ MVP

**User Story:** As an operator, I want each integration's circuit-breaker thresholds to
be configurable independently, so that we can tune them per downstream without code
changes.

**Acceptance Criteria:**

7. **WHEN** `application.properties` is read **THEN** the file SHALL contain
   `resilience4j.circuitbreaker.instances.<name>.*` blocks for each of the four ports,
   each with `failure-rate-threshold`, `sliding-window-size`,
   `wait-duration-in-open-state`, `permitted-number-of-calls-in-half-open-state`, and
   `minimum-number-of-calls`.
8. **WHEN** a property is overridden via environment variable (`RESILIENCE4J_*`) **THEN**
   the runtime SHALL pick up the new value without code changes.

### P1: C-8 interrupt flag is preserved ⭐ MVP

**User Story:** As a maintainer, I want adapter `Thread.sleep` catch blocks to preserve
the interrupt flag and rethrow a typed exception, so that thread pools can shut down
cleanly and downstream code (consumers, executors) can react to interrupts.

**Acceptance Criteria:**

9. **WHEN** an adapter is interrupted during its simulated sleep **THEN** the catch
   block SHALL call `Thread.currentThread().interrupt()` before rethrowing.
10. **WHEN** the adapter rethrows **THEN** it SHALL throw a typed
    `IntegrationAdapterException extends RuntimeException` (not raw `RuntimeException`),
    so that callers and Resilience4j see a recognisable exception class.

### P2: Metrics are emitted (consumed by F-OBSERVABILITY)

**User Story:** As an operator, I want circuit-breaker state and call counts to be
queryable via Micrometer, so that F-OBSERVABILITY can build dashboards on them with no
extra code change.

**Acceptance Criteria:**

11. **WHEN** Resilience4j Micrometer bindings are on the classpath **THEN** the
    application SHALL register
    `resilience4j.circuitbreaker.state{name,state}` and
    `resilience4j.circuitbreaker.calls{name,kind}` meters automatically.
12. **WHEN** F-OBSERVABILITY adds the Actuator Prometheus endpoint **THEN** the same
    meters SHALL be scraped without further wiring.

---

## Requirement Traceability

| Requirement ID | Story | Status |
| --- | --- | --- |
| RES-01 | P1 — Four named breakers registered | Planned |
| RES-02 | P1 — Adapter throw → CB records failure | Planned |
| RES-03 | P1 — Threshold crossing transitions to OPEN | Planned |
| RES-04 | P1 — OPEN state throws `CallNotPermittedException` | Planned |
| RES-05 | P1 — Wait-duration elapses → HALF_OPEN | Planned |
| RES-06 | P1 — Probe success → CLOSED | Planned |
| RES-07 | P1 — Per-port config in `application.properties` | Planned |
| RES-08 | P1 — ENV-driven overrides | Planned |
| RES-09 | P1 — Interrupt flag preserved | Planned |
| RES-10 | P1 — Typed `IntegrationAdapterException` | Planned |
| RES-11 | P2 — Micrometer bindings registered | Planned |
| RES-12 | P2 — Meters scrapeable by F-OBSERVABILITY | Planned |

---

## Success Criteria

- [ ] `./mvnw verify` is green with the four breakers wired and 4 new unit tests
  proving the OPEN/HALF_OPEN/CLOSED lifecycle.
- [ ] Each port's `Thread.sleep` is wrapped in a catch block that restores the
  interrupt flag and rethrows `IntegrationAdapterException`.
- [ ] `application.properties` has a `resilience4j.circuitbreaker.instances.*` block
  per port.
- [ ] CONCERNS C-8 is marked resolved with a residual-risk note (if any).
- [ ] The DLT-flow integration test (`InvoiceKafkaFlowIntegrationTest`) still passes
  end-to-end — Resilience4j does not interfere with the happy path.
