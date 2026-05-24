# F-RATELIMIT — Per-IP rate limiting via resilience4j-ratelimiter Specification

**Status:** Draft (2026-05-24)
**Milestone:** M5 — Abuse protection (new)
**Scope decision (user, 2026-05-24):**
- **Library:** `io.github.resilience4j:resilience4j-ratelimiter` (already on the
  classpath transitively via the F-RESILIENCE `resilience4j-spring-boot3` starter —
  AD-026). No new top-level dependency.
- **Coverage:** every `/api/**` endpoint is rate-limited, with **per-endpoint group
  limits** that match each endpoint's role (tight on `/api/auth/login` for brute-force
  protection, looser on the invoice endpoints, etc.). `/actuator/**` is **exempt** —
  see RLIM-OOS-1 for the rationale.
- **Key:** per-client-IP, resolved from `X-Forwarded-For` (first hop) with fallback to
  `HttpServletRequest.getRemoteAddr()`.
- **Implementation seam:** a Servlet `Filter` wired into the existing F-AUTH
  `SecurityFilterChain` **before** the JWT authentication filter, so anonymous traffic
  is throttled before any token validation cost is paid.
- **Error contract:** HTTP 429 + JSON body `{"codigo":"RATE_LIMIT_EXCEEDED","mensagem":"..."}`
  (same envelope as `UNSUPPORTED_TAX_REGIME` / `INVALID_CREDENTIALS` / etc.) +
  `Retry-After: <seconds>` header derived from the limiter's `refresh-period`.

## Problem Statement

The roadmap's *Future Considerations* lists "Rate limiting on `/api/auth/login`
(Bucket4j or Spring rate-limit)" as a hardening follow-up to F-AUTH. The current API
has no abuse protection at all:

- `POST /api/auth/login` is publicly reachable and accepts unlimited attempts per
  second. The two demo users (`demo`/`demo123`, `admin`/`admin123`) plus the
  `InMemoryUserStore` make this a textbook brute-forceable surface, even with BCrypt.
- `POST /api/orders/generate-invoice` (and its legacy alias) is authenticated but
  has no per-client throttling. A misbehaving (or simply buggy) client can flood the
  Kafka producer path and degrade the F-OBSERVABILITY SLIs for every other tenant.
- The legacy alias `POST /api/pedido/gerarNotaFiscal` shares the same risk profile.

The user has decided to close the gap with `resilience4j-ratelimiter` rather than
Bucket4j or Spring Cloud Gateway — Resilience4j is already in use for the four
outbound circuit breakers (AD-026), so reusing the same library keeps:

1. One resilience configuration namespace (`resilience4j.*` in
   `application.properties`).
2. One Micrometer binding for resilience metrics (already auto-published by
   `resilience4j-micrometer`); no extra collector is needed for F-OBSERVABILITY to
   start scraping rate-limit signals.

This is an **additive** feature: clients that stay under the limits see no behavioural
change. Clients that exceed limits see HTTP 429 with the same `{codigo, mensagem}`
envelope the rejection paths already use.

## Scope Decision Matrix

| Choice                         | Selected                                                          | Rationale                                                                                                                                                                                                                                                |
| ------------------------------ | ----------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Library                        | **`resilience4j-ratelimiter`**                                    | User pick. Already pulled in by `resilience4j-spring-boot3` (AD-026). Same Micrometer bindings as the circuit breakers — F-OBSERVABILITY scrape gets the new meters for free.                                                                            |
| Coverage                       | **All `/api/**` endpoints, per-endpoint groups; `/actuator/**` exempt** | Every public-API surface is throttled with limits sized to its risk profile. Actuator stays exempt to preserve the F-OBSERVABILITY Prometheus scrape contract (AUTH-15) and Kubernetes liveness/readiness probes — those are operational, not API. |
| Key                            | **Per client IP** (`X-Forwarded-For` first hop, fallback `getRemoteAddr()`) | Login has no JWT yet to derive a user-key. Per-IP is the universal default. Documented limitation: clients behind shared NAT share a bucket; acceptable for a demo with future per-subject upgrade documented under Future Considerations.            |
| Implementation seam            | **Servlet `Filter` in `SecurityFilterChain`, BEFORE the JWT filter** | Throttles anonymous traffic before paying the BCrypt / JWT verification cost. `@RateLimiter` annotation rejected: runs *after* the filter chain, no easy way to key on IP, and forces a new `@ExceptionHandler` for `RequestNotPermitted`.            |
| Configuration                  | **`resilience4j.ratelimiter.instances.<name>.*` in `application.properties`** | Same property style as the existing circuit-breaker config. Externalised so SREs can tune `limit-for-period` / `limit-refresh-period` per environment without code changes.                                                                          |
| Bucket waiting behaviour       | **`timeout-duration: 0`** (no wait, fail fast)                    | The filter must return 429 immediately when out of permits — blocking the request thread to wait for refill would defeat the abuse-protection point and hold up Tomcat worker threads.                                                                |
| Error envelope                 | **`{"codigo":"RATE_LIMIT_EXCEEDED","mensagem":"..."}` + `Retry-After: <sec>`** | Mirrors the `ApiExceptionHandler` envelope (UNSUPPORTED_TAX_REGIME, INVALID_CREDENTIALS, INVALID_DELIVERY_REGION, ...). `Retry-After` derived from the limiter's `limit-refresh-period`. RFC 6585 (HTTP 429) and RFC 7231 §7.1.3 (Retry-After).      |
| Per-endpoint groups (limits)   | **3 groups, see RLIM-09**                                         | `auth-login` (strict, anti-brute-force), `invoice-generate` (moderate, business throughput), `default` (catch-all under `/api/**` for any future endpoint). Numbers are tunable in properties; the spec freezes the *grouping*, not the integers.    |
| Spring layer placement         | **`adapter/security/ratelimit/`**                                 | Clean Architecture: only adapter code touches Resilience4j. `domain/` and `application/` stay free of Spring Security / Resilience4j imports. Mirrors the existing `adapter/security/login`, `adapter/security/error` package structure (AD-032).   |
| Test strategy                  | **Real filter chain integration test hammering a real endpoint**   | Same lesson as AD-029 (F-OBSERVABILITY: a bean registered ≠ a bean exercised) and AD-032 (F-AUTH `JwtTestSupport` over `@WithMockUser`). A unit test of the filter alone would not prove the filter is wired into the chain or that the bucket actually depletes across requests. |
| Metrics                        | **Auto-exposed via `resilience4j-micrometer`** (already on classpath, AD-026) | `resilience4j.ratelimiter.available.permissions{name}` and `resilience4j.ratelimiter.waiting.threads{name}` ship for free. F-OBSERVABILITY `/actuator/prometheus` scrape picks them up with no code change. No new SLI promoted (see RLIM-OOS-5).    |

## Goals

- [ ] **RLIM-G1:** A client that issues 5 rapid `POST /api/auth/login` attempts from
      the same IP receives `HTTP 200` (or 401) on the first 5 and `HTTP 429` on the
      6th, with `Retry-After: <sec>` and body
      `{"codigo":"RATE_LIMIT_EXCEEDED","mensagem":"..."}`.
- [ ] **RLIM-G2:** A client that issues `>limit-for-period` rapid
      `POST /api/orders/generate-invoice` requests from the same IP (with a valid JWT)
      receives `HTTP 429` on the overflow request with the same envelope and header.
- [ ] **RLIM-G3:** `/actuator/health`, `/actuator/health/**`, `/actuator/info`,
      `/actuator/prometheus` are **never** rate-limited. The F-OBSERVABILITY
      Prometheus scrape contract (AUTH-15) is preserved bit-for-bit; k8s liveness /
      readiness probes are unaffected.
- [ ] **RLIM-G4:** Two clients behind different IPs do not share a bucket — IP `A`
      exhausting the login bucket does not block IP `B`.
- [ ] **RLIM-G5:** `resilience4j.ratelimiter.available.permissions{name=auth-login}`
      and the same for `invoice-generate` show up on `GET /actuator/prometheus` once
      the filter has serviced at least one request through each group.
- [ ] **RLIM-G6:** All existing 103 fast tests continue to pass. New integration
      tests cover the throttling behaviour, the per-endpoint groups, the
      `Retry-After` header, and the per-IP isolation.
- [ ] **RLIM-G7:** `ROADMAP.md` introduces M5 with F-RATELIMIT as the single
      feature and flips it to COMPLETE on landing. `STATE.md` records a new ADR
      capturing the four key design choices (library, coverage, key, error
      envelope).

## Out of Scope

| Item                                                                | Reason                                                                                                                                                                                                  | Tracker        |
| ------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- |
| Rate limiting on `/actuator/**`                                     | Prometheus scrape happens every 15s — even a generous limit would eventually starve the scrape and break F-OBSERVABILITY SLIs. Liveness/readiness probes have the same problem. Operational ≠ API.   | RLIM-OOS-1     |
| Per-subject (per-user) buckets after auth                           | The user picked per-IP for simplicity. Per-subject is the natural production upgrade (mixed-NAT clients) — captured in Future Considerations.                                                            | RLIM-OOS-2     |
| Distributed rate limiting across multiple Fargate tasks             | Resilience4j-ratelimiter is **in-process**. Two ECS tasks behind the ALB → MSK each enforce the limit independently; effective per-IP rate is `tasks × limit-for-period`. Same demo-grade trade-off as `IdempotencyStore` (AD-024). | RLIM-OOS-3     |
| Bucket4j / Spring Cloud Gateway / NGINX rate limiting               | User explicitly chose `resilience4j-ratelimiter`.                                                                                                                                                       | RLIM-OOS-4     |
| Promoting rate-limit signals to an SLI                              | The F-OBSERVABILITY SLI catalog is frozen at four (AD-017). Adding a fifth would re-open that decision. Rate-limit meters remain queryable; no dashboard/alarm promotion.                              | RLIM-OOS-5     |
| Allowlist / denylist of IPs (whitelisting load testers, blocking abusers) | Belongs in a separate hardening feature (WAF / API Gateway rules). Resilience4j is a quota mechanism, not an access-control mechanism.                                                                | RLIM-OOS-6     |
| Sliding-window or token-bucket algorithm tuning                     | Resilience4j ships a fixed-window `RateLimiter` by default. Algorithm choice is internal to the library; the spec only freezes the *contract* (per-IP, fail-fast, 429 + Retry-After).                | RLIM-OOS-7     |
| Adaptive limits (raise/lower based on system load)                  | Out of demo scope. Captured in Future Considerations.                                                                                                                                                  | RLIM-OOS-8     |
| Rate limiting at the API Gateway (AWS WAF / API Gateway throttling) | Belongs in a follow-up F-AWS-RATELIMIT feature once F-RATELIMIT proves the contract at the app layer. AWS WAF rules are the production target for the edge boundary.                                  | RLIM-OOS-9     |

---

## User Stories

### P1: Login is brute-force-throttled ⭐ MVP

**User Story:** As a security-conscious operator, I want `POST /api/auth/login` to
return HTTP 429 once the configured per-IP rate is exceeded, so that an attacker
cannot brute-force the two demo passwords by issuing thousands of attempts per
second.

**Why P1:** This is the single most exposed surface in the app. F-AUTH explicitly
notes "no rate limiting" as a follow-up. Without RLIM-01..RLIM-05 the demo is
trivially brute-forceable end-to-end.

**Acceptance Criteria:**

- [ ] **RLIM-01:** `POST /api/auth/login` from IP `X` is allowed until the
      `auth-login` limiter for IP `X` runs out of permits within
      `limit-refresh-period`. The first `limit-for-period` requests are processed
      normally (200 on valid credentials, 401 on bad credentials — the rate limiter
      runs *before* credential validation but does not change the per-request
      outcome of allowed requests).
- [ ] **RLIM-02:** The next request from the same IP within the same refresh window
      returns `HTTP 429` with body
      `{"codigo":"RATE_LIMIT_EXCEEDED","mensagem":"..."}` and header
      `Retry-After: <seconds-until-refresh>` where the integer value is
      `ceil(limit-refresh-period in seconds)`.
- [ ] **RLIM-03:** The 429 response body **never** echoes the rejected credentials
      or any hint about whether the username existed — same hygiene rule as
      AUTH-06 / AUTH-07.
- [ ] **RLIM-04:** Two IPs (`A`, `B`) requesting the login endpoint in parallel
      have independent buckets. IP `A` being throttled does **not** cause IP `B`
      to receive 429.
- [ ] **RLIM-05:** After `limit-refresh-period` elapses, IP `A` recovers its full
      permit allowance — the next request returns to 200/401, not 429.

### P1: Invoice endpoints are throughput-throttled ⭐ MVP

**User Story:** As an SRE, I want `POST /api/orders/generate-invoice` (and the
legacy alias) to enforce a per-IP request ceiling, so that one misbehaving client
cannot saturate the Kafka producer path and degrade SLIs for every other tenant.

**Why P1:** Even authenticated clients can misbehave (retry loops, runaway tests).
Without this, F-OBSERVABILITY SLI-2 (API latency, 99 % < 800 ms) and SLI-3 (Kafka
dispatch success, 99.9 %) are at the mercy of any single client's behaviour.

**Acceptance Criteria:**

- [ ] **RLIM-06:** `POST /api/orders/generate-invoice` from IP `X` is allowed
      until the `invoice-generate` limiter for IP `X` runs out of permits within
      `limit-refresh-period`.
- [ ] **RLIM-07:** Overflow requests return `HTTP 429` with the same envelope
      and `Retry-After` header as RLIM-02.
- [ ] **RLIM-08:** The legacy alias `POST /api/pedido/gerarNotaFiscal` shares
      the same `invoice-generate` limiter — both URIs deplete and recover the
      same bucket for a given IP, so a client cannot bypass the limit by
      alternating endpoints.

### P1: Per-endpoint groups with sensible limits

**User Story:** As a tech lead, I want different per-endpoint groups with limits
that reflect each endpoint's risk profile, so that the brute-force surface is
tight and the business endpoint stays usable.

**Why P1:** A single global limit either starves business traffic (if set tight
for login) or leaves login wide open (if set loose for business). Per-endpoint
groups are the whole point of running multiple `RateLimiter` instances.

**Acceptance Criteria:**

- [ ] **RLIM-09:** Three `RateLimiter` instances are declared in
      `application.properties` under `resilience4j.ratelimiter.instances.*`:

      | Instance name        | URI pattern(s)                                                                | Initial `limit-for-period` | `limit-refresh-period` | `timeout-duration` | Rationale                                                              |
      | -------------------- | ----------------------------------------------------------------------------- | -------------------------- | ---------------------- | ------------------ | ---------------------------------------------------------------------- |
      | `auth-login`         | `POST /api/auth/login`                                                        | 5                          | 1m                     | 0                  | Anti-brute-force. Allows interactive retries (typos), blocks scripts.  |
      | `invoice-generate`   | `POST /api/orders/generate-invoice`, `POST /api/pedido/gerarNotaFiscal`       | 30                         | 1m                     | 0                  | Business throughput — comfortably above any interactive UI usage.      |
      | `default`            | every other `/api/**` request (catch-all for future endpoints)                | 60                         | 1m                     | 0                  | Catch-all so new endpoints are throttled from day one without spec change. |

- [ ] **RLIM-10:** A `RateLimitPolicy` (in `adapter/security/ratelimit/`) maps URI
      patterns to instance names. New endpoints fall through to `default`
      automatically — the spec freezes the three groups, not the URI table.
- [ ] **RLIM-11:** The integer values in RLIM-09 are properties, not literals in
      code. SREs can tune `limit-for-period` / `limit-refresh-period` per profile
      (`application-local.properties`, `application-aws.properties`, …) without
      a code change.

### P1: Actuator stays exempt

**User Story:** As an operator, I want `/actuator/health`, `/actuator/health/**`,
`/actuator/info`, and `/actuator/prometheus` to remain unthrottled, so that the
Prometheus scrape (every 15s) and Kubernetes liveness/readiness probes are never
falsely throttled.

**Why P1:** A throttled `/actuator/prometheus` would zero out F-OBSERVABILITY's
entire scrape window and trigger every SLO alert simultaneously. A throttled
`/actuator/health` would mark the pod unhealthy and trigger restart loops.

**Acceptance Criteria:**

- [ ] **RLIM-12:** Repeated `GET /actuator/health` from the same IP at scrape
      cadence (1 req/s for 30s = 30 requests) **never** returns 429.
- [ ] **RLIM-13:** Repeated `GET /actuator/prometheus` from the same IP at
      scrape cadence (1 req/s for 30s) **never** returns 429.
- [ ] **RLIM-14:** The rate-limit filter recognises the actuator path prefix and
      short-circuits before invoking any `RateLimiter` — actuator requests must
      not consume permits from `default` or any other instance.
- [ ] **RLIM-15:** The exemption is documented in code (Javadoc on the filter)
      and in `docs/observability.md` so the next operator does not "tidy up the
      filter to cover everything".

### P1: Per-IP isolation

**User Story:** As an API consumer, I want my requests to be evaluated against my
own IP's bucket, so that a noisy neighbour on a different IP cannot accidentally
get me throttled.

**Acceptance Criteria:**

- [ ] **RLIM-16:** The filter resolves the client IP via the first hop of
      `X-Forwarded-For` (if present) else `HttpServletRequest.getRemoteAddr()`.
- [ ] **RLIM-17:** The chosen IP is the lookup key for the per-instance
      `RateLimiter` (`registry.rateLimiter(<instance-name> + ":" + <ip>)` or
      equivalent — exact key shape is an implementation detail captured in
      `design.md`, not in this spec).
- [ ] **RLIM-18:** A unit / integration test proves that two different
      `X-Forwarded-For` values exercise two independent buckets.
- [ ] **RLIM-19:** Malformed `X-Forwarded-For` (empty, all-whitespace) falls
      back to `getRemoteAddr()` without throwing — the filter must not produce
      HTTP 500.

### P2: Honest error contract

**User Story:** As an API client, I want the 429 response to follow the same
`{codigo, mensagem}` envelope every other rejection uses, so that my error
handler does not need a special branch for rate-limit responses.

**Acceptance Criteria:**

- [ ] **RLIM-20:** 429 response body is JSON: `{"codigo":"RATE_LIMIT_EXCEEDED","mensagem":"<human-readable>"}`.
- [ ] **RLIM-21:** 429 response includes `Retry-After: <integer-seconds>` —
      computed as the ceiling of the configured `limit-refresh-period` in
      seconds, **not** the precise wait time to the next available permit
      (Resilience4j does not expose the latter on its fixed-window RateLimiter
      cleanly, and the conservative ceiling is honest about the next *guaranteed*
      refill).
- [ ] **RLIM-22:** 429 response `Content-Type` is `application/json` to match
      every other error response shape produced by `ApiExceptionHandler`.
- [ ] **RLIM-23:** A new `RejectionCode.RATE_LIMIT_EXCEEDED` enum constant is
      added so the rejection vocabulary stays centralised (today's enum lists
      `UNSUPPORTED_TAX_REGIME`, `INVALID_TAX_REGIME`, `INVALID_DELIVERY_REGION`,
      `INVALID_CREDENTIALS`, `INVALID_LOGIN_PAYLOAD`, `UNAUTHORIZED`,
      `FORBIDDEN`).

### P2: Metrics exposed automatically

**User Story:** As an SRE, I want rate-limit signals to show up on
`/actuator/prometheus` without any extra collector code, so that I can build a
quick dashboard panel when needed without re-touching the codebase.

**Acceptance Criteria:**

- [ ] **RLIM-24:** `resilience4j-micrometer` (already on the classpath via
      AD-026) auto-publishes:
      - `resilience4j.ratelimiter.available.permissions{name=auth-login}`
      - `resilience4j.ratelimiter.available.permissions{name=invoice-generate}`
      - `resilience4j.ratelimiter.available.permissions{name=default}`
      - `resilience4j.ratelimiter.waiting.threads{name=...}` (always 0 here
        because `timeout-duration=0`, but the meter shows up for free).
- [ ] **RLIM-25:** A scrape of `/actuator/prometheus` after at least one request
      through each instance returns lines containing the meter names above.
- [ ] **RLIM-26:** **No `clientIp` / `X-Forwarded-For` value is ever used as a
      Micrometer tag.** Per-IP signals stay in logs / traces only — same
      cardinality guard as AD-020.

### P3: Existing tests stay green

**User Story:** As a contributor, I want every existing integration test to keep
running, so that the new filter does not regress F-AUTH, F-OBSERVABILITY, or any
characterisation test.

**Acceptance Criteria:**

- [ ] **RLIM-27:** Tests that already replay 30+ requests against a single
      endpoint in the same test class (if any) either reset the limiter
      between tests or set a generous test-only override under
      `application-test.properties` (`resilience4j.ratelimiter.instances.*.limit-for-period=1000`)
      — the test profile must not be falsely-throttled by F-RATELIMIT defaults.
- [ ] **RLIM-28:** New integration tests (one class, ~6 tests) cover RLIM-01,
      RLIM-04, RLIM-05, RLIM-06, RLIM-12, RLIM-18 end-to-end through the real
      filter chain (mirroring the `JwtTestSupport` pattern from AD-032).
- [ ] **RLIM-29:** `./mvnw verify` is green; total fast test count grows from
      103 to ~109, with no test deletions.
- [ ] **RLIM-30:** The Newman / Postman collection is updated to document the
      new contract — at minimum a `06 - Rate Limit — 429 envelope` request that
      pre-runs the login endpoint enough times to trip the limit and asserts
      `pm.response.code === 429` + `codigo === "RATE_LIMIT_EXCEEDED"` +
      `pm.response.headers.has("Retry-After")`.

### P3: Honest documentation

**User Story:** As a future reviewer, I want the docs to explain what
F-RATELIMIT does, where the in-process limit lives, and what the production
upgrade path looks like, so that nobody mistakes the demo-grade in-process
limit for a distributed quota.

**Acceptance Criteria:**

- [ ] **RLIM-31:** `docs/business-rules.md` adds a "Rate limiting" section
      with the per-group table and the 429 envelope contract.
- [ ] **RLIM-32:** `docs/observability.md` adds a "Rate-limit signals"
      sub-section listing the four auto-published meters and a sample
      Prometheus query for "permissions exhausted in the last 5 minutes".
- [ ] **RLIM-33:** `README.md` and `README-CHALLENGE.md` flip the relevant
      hardening row (or add one) pointing to F-RATELIMIT.
- [ ] **RLIM-34:** `STATE.md` gets a new ADR (`AD-033`) recording the four
      key design choices: `resilience4j-ratelimiter` over Bucket4j,
      per-endpoint groups + actuator-exempt, per-IP key with
      `X-Forwarded-For` fallback, `Filter`-in-SecurityFilterChain over
      `@RateLimiter` annotation.
- [ ] **RLIM-35:** `ROADMAP.md` introduces M5 — Abuse protection, lists
      F-RATELIMIT as its single feature, and flips to COMPLETE on landing.
- [ ] **RLIM-36:** `CLAUDE.md` gains a "Rate limiting" subsection under the
      F-RATELIMIT defect-status block (next to F-RESILIENCE) describing the
      filter placement, the three instance names, and the 429 contract.

---

## Edge Cases

- WHEN `X-Forwarded-For` carries multiple comma-separated hops THEN the filter
  SHALL take the **first** (leftmost) hop as the client IP, per the de-facto
  convention where the leftmost entry is the original client and trailing
  entries are intermediate proxies.
- WHEN `X-Forwarded-For` is set but empty or only whitespace THEN the filter
  SHALL fall back to `getRemoteAddr()` and SHALL NOT throw.
- WHEN `getRemoteAddr()` itself returns `null` (degenerate test setups) THEN
  the filter SHALL use the literal string `"unknown"` as the bucket key — all
  such requests share one bucket, which is the safe failure mode (over-throttle,
  never under-throttle).
- WHEN the same IP issues `limit-for-period + 1` requests in parallel (race)
  THEN exactly one request SHALL be rejected with 429; the rest SHALL pass.
  Resilience4j's `RateLimiter` is thread-safe and provides this guarantee.
- WHEN an authenticated client's JWT has expired but the request is still
  under the IP's `invoice-generate` limit THEN the filter SHALL pass the
  request through to the JWT filter, which SHALL return 401 — rate limiting
  runs first by design (anti-brute-force), but auth failure still trumps a
  bucket-available outcome.
- WHEN `application.properties` defines an instance referenced by the policy
  table but the integer values are missing THEN Resilience4j SHALL fall back
  to its library defaults (50 permits / 500 ns refresh); the policy SHALL
  log a WARN at startup so the misconfiguration is visible.
- WHEN the filter is hit by a `OPTIONS` (CORS preflight) request THEN it
  SHALL pass through without consuming a permit — preflight is per-browser
  cache management, not user-driven traffic.

---

## Requirement Traceability

Each requirement gets a unique ID for tracking across design, tasks, and validation.

| Requirement ID | Story                                | Phase  | Status  |
| -------------- | ------------------------------------ | ------ | ------- |
| RLIM-01        | P1: Login throttled                  | Tasks  | Pending |
| RLIM-02        | P1: Login throttled                  | Tasks  | Pending |
| RLIM-03        | P1: Login throttled                  | Tasks  | Pending |
| RLIM-04        | P1: Login throttled                  | Tasks  | Pending |
| RLIM-05        | P1: Login throttled                  | Tasks  | Pending |
| RLIM-06        | P1: Invoice throttled                | Tasks  | Pending |
| RLIM-07        | P1: Invoice throttled                | Tasks  | Pending |
| RLIM-08        | P1: Invoice throttled                | Tasks  | Pending |
| RLIM-09        | P1: Per-endpoint groups              | Tasks  | Pending |
| RLIM-10        | P1: Per-endpoint groups              | Tasks  | Pending |
| RLIM-11        | P1: Per-endpoint groups              | Tasks  | Pending |
| RLIM-12        | P1: Actuator exempt                  | Tasks  | Pending |
| RLIM-13        | P1: Actuator exempt                  | Tasks  | Pending |
| RLIM-14        | P1: Actuator exempt                  | Tasks  | Pending |
| RLIM-15        | P1: Actuator exempt                  | Tasks  | Pending |
| RLIM-16        | P1: Per-IP isolation                 | Tasks  | Pending |
| RLIM-17        | P1: Per-IP isolation                 | Tasks  | Pending |
| RLIM-18        | P1: Per-IP isolation                 | Tasks  | Pending |
| RLIM-19        | P1: Per-IP isolation                 | Tasks  | Pending |
| RLIM-20        | P2: Error contract                   | Tasks  | Pending |
| RLIM-21        | P2: Error contract                   | Tasks  | Pending |
| RLIM-22        | P2: Error contract                   | Tasks  | Pending |
| RLIM-23        | P2: Error contract                   | Tasks  | Pending |
| RLIM-24        | P2: Metrics exposed                  | Tasks  | Pending |
| RLIM-25        | P2: Metrics exposed                  | Tasks  | Pending |
| RLIM-26        | P2: Metrics exposed                  | Tasks  | Pending |
| RLIM-27        | P3: Existing tests stay green        | Tasks  | Pending |
| RLIM-28        | P3: Existing tests stay green        | Tasks  | Pending |
| RLIM-29        | P3: Existing tests stay green        | Tasks  | Pending |
| RLIM-30        | P3: Existing tests stay green        | Tasks  | Pending |
| RLIM-31        | P3: Honest documentation             | Tasks  | Pending |
| RLIM-32        | P3: Honest documentation             | Tasks  | Pending |
| RLIM-33        | P3: Honest documentation             | Tasks  | Pending |
| RLIM-34        | P3: Honest documentation             | Tasks  | Pending |
| RLIM-35        | P3: Honest documentation             | Tasks  | Pending |
| RLIM-36        | P3: Honest documentation             | Tasks  | Pending |

**ID format:** `RLIM-NN`.
**Status values:** Pending → In Design → In Tasks → Implementing → Verified.
**Coverage:** 36 total, 0 mapped to tasks yet (tasks.md to follow).

---

## Success Criteria

F-RATELIMIT is COMPLETE when:

1. ✅ `./mvnw verify` is green.
2. ✅ A bash one-liner (`for i in {1..6}; do curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"demo","password":"demo123"}'; done`) returns `200`, `200`, `200`, `200`, `200`, `429` (or `401`s in place of `200`s if the bad password path is taken first — the 6th MUST be `429`).
3. ✅ The 429 response body parses as JSON with `codigo === "RATE_LIMIT_EXCEEDED"` and the response carries a `Retry-After` integer-seconds header.
4. ✅ `GET /actuator/prometheus` returns lines containing
   `resilience4j_ratelimiter_available_permissions{name="auth-login",...}` (and
   the equivalent for `invoice-generate`, `default`) after at least one request
   has hit each group.
5. ✅ `newman run docs/postman/invoice-generator.postman_collection.json` is
   green end-to-end (with the new RLIM-30 request).
6. ✅ `ROADMAP.md` lists F-RATELIMIT under M5 with status COMPLETE.
7. ✅ `STATE.md` records `AD-033`.
8. ✅ `docs/business-rules.md`, `docs/observability.md`, `README.md`,
   `README-CHALLENGE.md`, `CLAUDE.md` all reference the new rate-limit contract.

---

## Future Considerations

- **Per-subject (per-user) buckets** for authenticated endpoints once the user
  count grows beyond demo-grade — keyed on JWT `sub` claim instead of IP, so
  shared-NAT clients each get their own quota. Captured under RLIM-OOS-2.
- **Distributed rate limiting** (Redis, ElastiCache) so multi-task ECS Fargate
  deployments enforce a single tenant-wide quota instead of `tasks × limit`.
  Required before production rollout of more than one task. Captured under
  RLIM-OOS-3.
- **AWS WAF / API Gateway throttling** at the edge so the abusive-IP traffic
  is rejected before it ever reaches Fargate — moves the cost from the app
  layer to the gateway. Captured under RLIM-OOS-9.
- **Adaptive limits** that raise/lower based on system load (e.g., raise
  permits when CPU < 50 %, lower when SLI-2 latency tail breaches budget).
  Captured under RLIM-OOS-8.
- **Allowlist for known load-testing IPs** (CI runners, synthetics) so
  monitoring traffic does not deplete production buckets. Captured under
  RLIM-OOS-6.
- **Promote a rate-limit SLI** ("% of requests rejected with 429" as an inverse
  health signal) — re-opens AD-017, so requires explicit user approval.
  Captured under RLIM-OOS-5.
