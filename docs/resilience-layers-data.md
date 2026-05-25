# Resilience layers: rate limit, circuit breaker, and bulkhead

The API combines three resilience patterns because each one covers a different
risk axis. They are **not redundant** — they are complementary protection layers.

| Pattern | What it protects | When it acts | Where it is configured |
| --- | --- | --- | --- |
| **Rate limit** | HTTP entry per client (IP) | Before spending JWT/BCrypt cost or enqueuing work | `application.properties` (`resilience4j.ratelimiter.instances.*`), `RateLimitFilter.java` |
| **Circuit breaker** | Outbound integrations when the downstream is failing | After a window of failures (reactive) | `application.properties` (`resilience4j.circuitbreaker.instances.*`), adapters in `adapter/integration/**` |
| **Bulkhead** | Excess concurrency per integration | Before failure, at call time (proactive) | `application.properties` (`resilience4j.bulkhead.instances.*`), [`bulkhead-strategy.md`](bulkhead-strategy.md) |

## 1. Rate limit — protects HTTP entry

Throttles clients per IP **before** any JWT validation, BCrypt hashing, or Kafka
publish cost is paid. When the bucket is empty, it responds with `HTTP 429`
immediately, using the `{codigo, mensagem}` envelope and a `Retry-After` header.

Configuration by group:

- `auth-login` — **5/min**. Brute-force defence on the login endpoint.
- `invoice-generate` — **30/min**. Canonical endpoint and legacy alias share the
  same bucket.
- `default` — **60/min**. Catch-all for any future `/api/**` endpoint.

`/actuator/**` is **exempt**, so Prometheus scrape and Kubernetes probes are
never falsely throttled.

Operational details and metrics: "Rate limiting" section in
[`business-rules.md`](business-rules.md) and "Rate-limit signals" section in
[`observability.md`](observability.md).

## 2. Circuit breaker — protects against a broken downstream

Lives on the four outbound adapters: **stock**, **invoice registration**,
**delivery**, and **accounts receivable**. If one of them starts failing too
much, the circuit **opens** and the next calls fail fast, instead of hammering a
service that is already broken.

This avoids:

- saturating Kafka consumers / retry chains with calls that will fail anyway;
- amplifying latency by piling up timeouts against a collapsing downstream;
- consuming the time the downstream needs to recover.

Configuration (per instance in `application.properties`):

- The circuit opens at **50%** failure rate within the sliding window.
- It stays **open for 10s**.
- Then transitions to **half-open** and probes a limited number of calls before
  deciding to close or re-open.

The `@CircuitBreaker(name="<port>")` annotation sits on each adapter method —
same name used in the configuration keys.

## 3. Bulkhead — protects against excess concurrency

Even when the downstream is **not failing**, it can be slow. The circuit breaker
only reacts **after** failures; the bulkhead prevents — **before the fact** — a
slow service from receiving too many concurrent calls.

It caps how many simultaneous calls each adapter can hold:

- `deliveryPort.max-concurrent-calls=5` — tighter, because it simulates
  `Thread.sleep(5000)` (permits stay held for 5s).
- `stock` / `registration` / `accountsReceivable` = **20**.

`max-wait-duration=0` everywhere: fail fast instead of parking threads waiting
for a permit. Rejected calls bubble up to Kafka `@RetryableTopic`.

**SEMAPHORE** variant only — `THREADPOOL` would force `CompletableFuture<T>` on
every port (the same trade-off AD-027 rejected for `@TimeLimiter`).

Dedicated operator-facing doc: [`bulkhead-strategy.md`](bulkhead-strategy.md).

## In one sentence

- **Rate limit** controls **entry speed** per client.
- **Bulkhead** controls **concurrency** on integrations.
- **Circuit breaker** **cuts traffic** to a broken integration.

## Order of application on a request

```
HTTP request
    │
    ▼
[Rate limit filter]  ──► 429 if the IP exhausted the group bucket
    │
    ▼
[JWT auth + controller + interactor]
    │
    ▼
[Bulkhead]           ──► rejects if N concurrent calls already in flight
    │
    ▼
[Circuit breaker]    ──► fails fast if the circuit is open
    │
    ▼
Simulated downstream (stock / registration / delivery / accounts receivable)
```

Every layer fails fast (`timeout-duration=0` / `max-wait-duration=0`) so the
next layer or the HTTP client receives the error without parking threads.
