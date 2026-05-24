# Bulkhead strategy

**Status:** Active (2026-05-24)
**Feature:** [F-BULKHEAD](../.specs/features/bulkhead/spec.md)
**ADR:** AD-033 in [STATE.md](../.specs/project/STATE.md)

This document explains **what bulkhead is**, **why we use the semaphore variant**, and **how the
`max-concurrent-calls` numbers in `application.properties` were chosen** for each of the four
outbound integration adapters.

---

## 1. The mental model — supermarket checkouts

A bulkhead is a **concurrency limit**, not a queue. Think of each outbound adapter as a
supermarket counter:

- **No bulkhead** = one counter, infinite line. 100 customers arrive → all 100 wait. Each takes
  5 seconds → 500 seconds to clear.
- **`max-concurrent-calls=5`** = **5 counters open in parallel**. Up to 5 customers are served
  at the same time. The **6th customer to arrive while all 5 counters are busy is turned away
  immediately** (Resilience4j throws `BulkheadFullException`). They are **not put in a queue**.

The whole point: **fail fast when the downstream is already saturated**, instead of piling more
work onto a struggling dependency.

```
                     incoming concurrent calls
                              │
                              ▼
                  ┌───────────────────────┐
                  │  Bulkhead semaphore   │
                  │  permits = 5 (delivery)│
                  └───────────────────────┘
                     │ │ │ │ │     ✗ ← 6th rejected (BulkheadFullException)
                     ▼ ▼ ▼ ▼ ▼
                  5 in-flight calls
                  to the downstream
```

---

## 2. Why we picked the **semaphore** variant, not the threadpool variant

Resilience4j ships two flavors:

| Variant | How it works | Trade-off |
| --- | --- | --- |
| `SEMAPHORE` (default) | Counts in-flight calls on the **caller's thread**. Lightweight. | Returns synchronously like a normal method call. |
| `THREADPOOL` | Hands work to its own thread pool, returns `CompletableFuture<T>`. | **Forces every adapter signature to become `CompletableFuture<T>`** — propagates through ports, the use case, and the consumers. |

We picked `SEMAPHORE` for the exact same reason AD-027 deferred `@TimeLimiter`: the threadpool
variant would force `CompletableFuture` everywhere with no measurable upside on simulated
`Thread.sleep` downstreams. Semaphore is a counter around the existing synchronous call — zero
signature changes.

---

## 3. Why each adapter needs its **own** bulkhead

If we shared one global bulkhead for all four adapters, a slow delivery call would hold permits
that a fast stock call could have used. **One adapter's slowness must not starve another.** This
is the entire reason the pattern is called "bulkhead" — like the watertight compartments in a
ship's hull, a flood in one section doesn't sink the rest.

The four adapters get four independently-tuned bulkheads:

```
                ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌──────────────┐
                │   stock    │  │registration│  │  delivery  │  │   finance    │
                │  (~380ms)  │  │  (~500ms)  │  │  (5000ms)  │  │   (~250ms)   │
                └────────────┘  └────────────┘  └────────────┘  └──────────────┘
                  permits=20      permits=20      permits=5       permits=20
```

---

## 4. Why **delivery** is tighter than the other three

The other three adapters do `Thread.sleep(250)` to `Thread.sleep(500)` — they release their
permit in **under a second**. Delivery does `Thread.sleep(5000)` — it holds its permit for
**five seconds**.

The mental rule: **slow adapter + high permit count is worse than slow adapter + low permit
count**, because a high count authorises the system to start more slow work in parallel.

Concretely:

- **20 fast (300ms) calls in flight** → all done in ~300ms. The downstream sees 20 quick hits.
- **20 slow (5s) calls in flight** → all 20 transactions are held open for 5 full seconds at
  the same time. If anything goes wrong in that 5s window (the downstream freezes, the JVM
  pauses, the network partitions), **20 transactions are simultaneously at risk** instead of 5.

Capping delivery at 5 limits the blast radius. We trade a slightly tighter throughput ceiling
(5 deliveries / 5s = 1 delivery / second steady-state) for a smaller failure surface.

---

## 5. The chosen numbers and how to read them

```properties
# application.properties
resilience4j.bulkhead.instances.stockPort.max-concurrent-calls=20
resilience4j.bulkhead.instances.invoiceRegistrationPort.max-concurrent-calls=20
resilience4j.bulkhead.instances.deliveryPort.max-concurrent-calls=5
resilience4j.bulkhead.instances.accountsReceivablePort.max-concurrent-calls=20
resilience4j.bulkhead.instances.<name>.max-wait-duration=0
```

**Reading the values:**

| Adapter | Simulated latency | `max-concurrent-calls` | "Customers turned away when…" |
| --- | --- | --- | --- |
| `stockPort` | `Thread.sleep(380)` | 20 | 21+ stock deductions in flight simultaneously |
| `invoiceRegistrationPort` | `Thread.sleep(500)` | 20 | 21+ registrations in flight simultaneously |
| `deliveryPort` | **`Thread.sleep(5000)`** | **5** | 6+ delivery schedules in flight simultaneously |
| `accountsReceivablePort` | `Thread.sleep(250)` | 20 | 21+ AR postings in flight simultaneously |

`max-wait-duration=0` means **fail-fast**: when permits are exhausted, the call is rejected
immediately instead of blocking the caller waiting for a permit to free up. Same posture as the
rate-limiter `timeout-duration=0` in F-RATELIMIT — blocking the consumer thread defeats the
back-pressure point.

---

## 6. Today's reality vs. the future the bulkhead protects

**Today** — the Spring Kafka listener concurrency for each integration consumer is **1** (the
Spring Kafka default; not customised in `KafkaMessagingConfig`). So in steady state, at most
1 call per adapter is ever in flight. **The bulkhead is rarely going to fire today** — it sits
at 0% utilisation most of the time.

**Why bother, then?** The bulkhead is **insurance against a future change**:

- If someone bumps `concurrency=10` on `DeliverySchedulingConsumer` to drain a backlog faster,
  the bulkhead caps the simultaneous downstream hits at 5 regardless. The 6th-10th messages
  bubble up to the `@RetryableTopic` retry path instead of overwhelming the (already slow)
  downstream.
- If a bug somewhere fires 1,000 parallel calls to `scheduleDelivery()`, the first 5 proceed
  and the other 995 fail fast — the downstream sees 5 calls, not 1,000.
- If a real HTTP downstream replaces the `Thread.sleep` stub and that downstream publishes a
  documented concurrency SLA ("we accept up to 10 concurrent calls per client"), the bulkhead
  is the seam where that contract is enforced.

The numbers are **tunable in `application.properties` per environment** without code changes.
SREs can lower them in production (smaller downstream SLA) or raise them locally (load tests)
without touching Java.

---

## 7. Interaction with the other Resilience4j patterns

| Pattern | What it reacts to | Where it lives |
| --- | --- | --- |
| **Circuit breaker** (F-RESILIENCE) | **Failures.** Opens when the failure rate exceeds 50%. Stops sending traffic to a known-broken downstream. | Same 4 adapters, annotation `@CircuitBreaker`. |
| **Bulkhead** (this doc, F-BULKHEAD) | **Concurrency.** Caps in-flight calls regardless of success/failure. | Same 4 adapters, annotation `@Bulkhead`. |
| **Rate limiter** (F-RATELIMIT) | **Rate.** Caps requests-per-second per client IP at the HTTP boundary. | Servlet filter in `SecurityFilterChain`, **not** on the outbound adapters. |

The three are **complementary, not redundant**:

- Circuit breaker says "the downstream is broken — stop calling it."
- Bulkhead says "the downstream is healthy but we're already calling it as hard as it can take."
- Rate limiter says "this client is hitting us too fast at the front door."

Each one catches a different failure mode. The bulkhead is the piece that was missing in the
"CB + rate limit at the edge" picture before F-BULKHEAD.

---

## 8. Decision log

**Date:** 2026-05-24
**Decided by:** project owner (user conversation)
**Choice:** option A — `delivery=5, others=20`, semaphore variant, fail-fast (`max-wait-duration=0`).

**Why option A specifically:**

- **5 for delivery** gives 5× the headroom over today's listener concurrency of 1 — enough
  slack for a future concurrency bump without authorising the slow downstream to absorb
  arbitrary parallel load.
- **20 for the others** is generous because they're fast. The bulkhead is essentially
  a guardrail against runaway loops, not a steady-state throttle.
- **Fail-fast (0 wait)** matches the rate-limiter posture and avoids holding the
  Kafka consumer thread waiting for a permit. Failed calls bubble to `@RetryableTopic`,
  which is the correct retry mechanism for transient back-pressure.
- **Properties, not literals**, so SREs tune per environment without a code change. The
  spec freezes the *grouping* (per-adapter bulkheads) and the *posture* (semaphore + fail-fast),
  not the integers.

**Options considered and rejected:**

| Option | Why not |
| --- | --- |
| `delivery=3, others=10` (more conservative) | Today's concurrency is 1; even 3 would rarely fire. The looser numbers give more future headroom for the same protection. |
| `delivery=5, stock=20, registration=10, finance=10` (finely calibrated) | No real signal yet to justify asymmetry among the three fast adapters. When real HTTP downstreams replace the stubs, this asymmetry can be reintroduced based on actual SLAs. |
| Single global bulkhead with `max-concurrent-calls=80` | One slow adapter would starve fast adapters. Defeats the whole "watertight compartments" point. |
| `THREADPOOL` variant | Forces `CompletableFuture<T>` on every port. Same trade-off AD-027 already rejected for `@TimeLimiter`. |
| Adding `@Bulkhead` only to delivery | Inconsistent — the other three would silently absorb any parallel-call bug. Worth four properties + one annotation per adapter for symmetry. |

---

## 9. Operational signals

`resilience4j-micrometer` (already on the classpath via AD-026) auto-publishes for each
bulkhead instance:

- `resilience4j.bulkhead.available.concurrent.calls{name}` — current free permits.
- `resilience4j.bulkhead.max.allowed.concurrent.calls{name}` — the configured ceiling.

These appear on `GET /actuator/prometheus` automatically after the first request flows
through each instance. No new SLI promoted (AD-017 keeps the SLI catalog at four); the
signals stay queryable for ad-hoc dashboards and incident investigation.

The cardinality budget from AD-020 applies: **`name` is bounded** (4 instance names), so it
is a safe metric tag. No per-invoice / per-correlation identifiers ever land on bulkhead
meters — same rule as the circuit-breaker and rate-limiter signals.

---

## 10. References

- [F-BULKHEAD spec](../.specs/features/bulkhead/spec.md)
- [F-RESILIENCE circuit-breaker companion](observability.md#resilience-signals)
- [Resilience4j Bulkhead documentation](https://resilience4j.readme.io/docs/bulkhead) (semaphore vs threadpool, configuration reference)
- AD-026 (`resilience4j-spring-boot3` starter — same dependency tree, no new top-level dep needed)
- AD-027 (why `@TimeLimiter` was deferred — same reasoning applies to `THREADPOOL` bulkhead)
- AD-033 (this decision)
