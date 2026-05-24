# F-RATELIMIT — Design

**Spec:** [`spec.md`](spec.md)
**Status:** Frozen (2026-05-24)

---

## Knowledge verification chain (per skill rules)

- **Codebase:**
  - `pom.xml` declares `io.github.resilience4j:resilience4j-spring-boot3:2.2.0`
    and `io.github.resilience4j:resilience4j-micrometer:2.2.0` (lines 48-56).
    The `RateLimiter` core type ships with `resilience4j-spring-boot3` — no new
    Maven coordinate is needed.
  - `adapter/security/SecurityConfig.java` already builds the canonical
    `SecurityFilterChain` bean (`csrf().disable()`, STATELESS,
    `oauth2ResourceServer().jwt()`, custom 401/403 handlers). We will **edit**
    this single chain to insert the new filter, not introduce a second chain.
  - `adapter/web/ApiExceptionHandler.java` returns `ErrorResponseDto(codigo,
    mensagem)` for every domain rejection. The 4xx envelope precedent is set;
    the rate-limit filter writes the same shape directly (it runs **before**
    `DispatcherServlet`, so `@RestControllerAdvice` cannot catch its
    rejections — the filter must serialize the envelope itself).
  - `adapter/observability/RejectionCode.java` is a metric-cardinality enum
    bound to `invoice.rejected{reason}`. It currently holds only
    `UNSUPPORTED_TAX_REGIME`, `INVALID_TAX_REGIME`, `INVALID_DELIVERY_REGION`.
    F-AUTH did **not** add `INVALID_CREDENTIALS` / `INVALID_LOGIN_PAYLOAD`
    here — those are literal strings in `ApiExceptionHandler` because they are
    not invoice-domain rejections. We follow the same precedent for
    `RATE_LIMIT_EXCEEDED` (see AD-RLIM-3).
  - `application.properties` already hosts the `resilience4j.circuitbreaker.instances.*`
    block (lines 44-74). The new `resilience4j.ratelimiter.instances.*` block
    sits next to it.

- **Project docs:**
  - `STATE.md` AD-026 documents the Resilience4j + Spring Boot 3 wiring
    decision; AD-020 freezes the cardinality budget (no per-IP tags on
    metrics); AD-017 freezes the four-SLI catalog (rate-limit signals are
    **queryable** meters, not promoted to a fifth SLI — RLIM-OOS-5).
  - `docs/observability.md` is the operator SSOT for what shows up on
    `/actuator/prometheus` and what the cardinality contract is.
  - `.specs/features/auth/design.md` is the closest sibling: filter-chain
    edits + custom 401/403 handlers + the `{codigo, mensagem}` envelope on an
    error generated below the controller layer.

- **Library reference (Resilience4j 2.2.0):**
  - `io.github.resilience4j.ratelimiter.RateLimiter` — core type.
    `RateLimiter.acquirePermission()` returns `boolean` (when
    `timeout-duration=0`); non-blocking.
    `RateLimiter.getRateLimiterConfig().getLimitRefreshPeriod()` returns the
    refresh-period `Duration` used for the `Retry-After` header.
  - `io.github.resilience4j.ratelimiter.RateLimiterRegistry` — Spring-managed
    bean auto-configured by `resilience4j-spring-boot3` when `RateLimiter`
    instances are declared under `resilience4j.ratelimiter.instances.*`.
    `registry.rateLimiter(String name)` returns (or creates) a limiter for
    that name; lookup is `ConcurrentHashMap`-backed.
  - `io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics` —
    auto-published by `resilience4j-micrometer` with `name` tag (the limiter
    name). Tags are **bounded by limiter name only**, so a per-IP key in the
    name would explode cardinality. We address this in AD-RLIM-2.

- **Spring Security filter ordering:**
  - `BearerTokenAuthenticationFilter` is the OAuth2 Resource Server filter
    installed by `oauth2ResourceServer().jwt()`. To run rate-limit checks
    **before** JWT validation, we attach the new filter with
    `http.addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)`.

---

## Architectural decisions

### AD-RLIM-1: Servlet `Filter` in `SecurityFilterChain` over `@RateLimiter` annotation

**Decision:** Implement rate limiting as a single `OncePerRequestFilter` named
`RateLimitFilter`, wired into the existing `SecurityFilterChain` via
`http.addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)`.
**Reject** the `@RateLimiter(name=...)` annotation approach (Resilience4j's
Spring AOP integration on controller methods).

**Reason:** Two reasons together:
1. **IP-keyed buckets.** The annotation runs *inside* the controller method,
   after the request body is parsed and after JWT validation. By that point
   we have already paid the BCrypt / token-decoding cost of an abuse
   request. A filter intercepts before any of that work.
2. **Single-source URI-to-instance mapping.** The annotation forces a
   `@RateLimiter` literal on every annotated method; the filter centralises
   the path-to-instance mapping in one `RateLimitPolicy` so a new endpoint
   inherits the `default` group automatically (RLIM-10).

**Trade-off:** `@RateLimiter` is the more idiomatic Resilience4j shape
(parallels `@CircuitBreaker` already used by AD-026). The filter approach
trades that idiom for the two wins above. The trade is recorded once here
so future readers do not "tidy this up" into annotations.

### AD-RLIM-2: Single `RateLimiter` per group, IP-keyed buckets via internal map

**Decision:** Each of the three groups (`auth-login`, `invoice-generate`,
`default`) is a **single** `RateLimiter` instance configured statically in
`application.properties`. Per-IP isolation is achieved by deriving a unique
`RateLimiter` instance per `(group, ip)` pair at runtime via
`registry.rateLimiter(group + ":" + ip, registry.getConfiguration(group).orElseThrow())`,
which on first use clones the group's config into a new named instance.

**Reason:** Resilience4j has no built-in "key extractor" for rate limiters
(unlike a Bucket4j `IpAddressLimitingRule`). The two options were:
- **Option A (rejected):** one `RateLimiter` per group, called as
  `acquirePermission()` — but the bucket would be global, violating
  RLIM-04 / RLIM-18 (per-IP isolation).
- **Option B (chosen):** synthesise a per-IP instance name on the fly.
  Resilience4j's `registry.rateLimiter(name, config)` is documented as
  thread-safe and returns the existing instance if one with that name
  already exists. Lookup cost is a `ConcurrentHashMap` get.

**Trade-off:**
1. **Memory growth.** One small `RateLimiter` object per unique IP per
   group, retained indefinitely (Resilience4j has no built-in TTL eviction).
   For a demo, fine; documented as a follow-up under RLIM-OOS-3 — production
   needs a TTL eviction policy or a distributed store.
2. **Micrometer cardinality.** `TaggedRateLimiterMetrics` exposes one tag
   (`name`) per registered instance. If we register one instance per IP, the
   meter name set explodes — directly violating AD-020. **Mitigation:** the
   per-IP synthesised instances are intentionally **not** registered with
   the global `MeterRegistry`. We do that by binding `TaggedRateLimiterMetrics`
   to a filtered registry view that publishes meters only for the three
   statically-named instances (`auth-login`, `invoice-generate`, `default`).
   The aggregate signal "permits available for the group" stays meaningful
   (it tracks the group's config), and per-IP signals are intentionally
   absent. The implementation is one `MeterFilter` rejecting the per-IP
   `name` values — see Component `RateLimiterMeterFilter`.

### AD-RLIM-3: Literal `"RATE_LIMIT_EXCEEDED"` string, not a `RejectionCode` enum entry

**Decision:** The 429 response body uses the literal string
`"RATE_LIMIT_EXCEEDED"` for the `codigo` field. We do **not** add
`RATE_LIMIT_EXCEEDED` to `adapter/observability/RejectionCode` (the existing
3-value enum is bound to the `invoice.rejected{reason}` business counter's
cardinality contract).

**Reason:** Follows the F-AUTH precedent — `ApiExceptionHandler` uses
literal strings (`"INVALID_CREDENTIALS"`, `"INVALID_LOGIN_PAYLOAD"`) for
auth codes for exactly the same reason. The `RejectionCode` enum has a
specific semantic (invoice-domain rejection metric tag), and stretching it
to cover transport-layer rejections would either pollute the
`invoice.rejected` counter or require a parallel enum with confusing
overlap.

**Trade-off:** No central registry of every possible `codigo` string.
Mitigation: this spec, the F-AUTH spec, and F-DEFECTS-FUNCTIONAL spec
together enumerate every code in their respective requirement tables. A
future hardening task could extract a transport-layer `ErrorCode` enum
covering all 4xx codes — captured under Future Considerations.

### AD-RLIM-4: `Retry-After` is `ceil(limit-refresh-period)`, not exact wait time

**Decision:** The `Retry-After` header value is the integer ceiling of the
configured `limit-refresh-period`, in seconds. We do **not** attempt to
compute the exact "seconds until the next permit becomes available".

**Reason:** Resilience4j's fixed-window `RateLimiter` does not expose the
"time-to-next-refill" cleanly. Approximating it would require reading
`getDetailedMetrics().getNanosToWait()`, which is internally specced for
the *blocking* `acquirePermission(timeout)` path. The configured refresh
period is the next *guaranteed* refill window, which is honest and
conservative (the client waits at most one refresh period, often less).

**Trade-off:** Clients that respect `Retry-After` strictly will back off
slightly longer than strictly needed. Acceptable for an abuse-protection
contract — a tighter value would risk thundering-herd retries hitting the
refresh boundary exactly.

### AD-RLIM-5: Test strategy — `@TestPropertySource` shrinks limits, distinct synthetic IPs per test

**Decision:** Integration tests for F-RATELIMIT live in a dedicated
`RateLimitIntegrationTest` class with a `@TestPropertySource` block that
overrides `resilience4j.ratelimiter.instances.auth-login.limit-for-period=3`
(small number, fast to trip). Each test method targets a **distinct
synthetic IP** via the `X-Forwarded-For` header (`10.0.0.1`, `10.0.0.2`, …)
so buckets do not leak between tests. Other integration tests
(`InvoiceControllerIntegrationTest`, `AuthControllerIntegrationTest`, …) use
the **default test-profile values** which raise every limit to `10000` so
those suites are never falsely throttled.

**Reason:** Same lesson as AD-029 (F-OBSERVABILITY: registered ≠ wired) and
AD-032 / AD-AUTH-4 (real tokens through the full chain). A real chain
proves the filter is installed, ordered correctly, and exercising the
registry — a unit test of `RateLimitFilter` alone would not.

**Trade-off:** A small per-class property override block in
`RateLimitIntegrationTest`. Negligible. The alternative — calling
`registry.rateLimiter("auth-login:<ip>").changeLimitForPeriod(3)` mid-test —
ties the test to internal Resilience4j API shape and is harder to read.

---

## Architecture Overview

```
HTTP request
  │
  ▼
┌──────────────────────────────────────────────────────────┐
│  SecurityFilterChain (existing F-AUTH bean, edited)      │
│                                                          │
│   ┌──────────────────────┐                               │
│   │  RateLimitFilter     │  ← NEW (addFilterBefore)      │
│   │  (OncePerRequest)    │                               │
│   └──────────┬───────────┘                               │
│              │  permit acquired                          │
│              ▼                                           │
│   ┌──────────────────────────────────┐                   │
│   │  BearerTokenAuthenticationFilter │  ← existing       │
│   └──────────┬───────────────────────┘                   │
│              ▼                                           │
│   ┌──────────────────────────┐                           │
│   │  Authorization checks     │                          │
│   │  (hasAuthority SCOPE_...) │                          │
│   └──────────┬───────────────┘                           │
└──────────────┼───────────────────────────────────────────┘
               ▼
        DispatcherServlet → Controllers


Inside RateLimitFilter.doFilterInternal():
  ┌────────────────────────────────────────────┐
  │ 1. policy.lookup(request.getRequestURI(),  │
  │                  request.getMethod())       │
  │      → instanceName (null = exempt)         │
  │                                             │
  │ 2. if instanceName == null → chain.doFilter │
  │                                             │
  │ 3. ip = clientIpResolver.resolve(request)   │
  │ 4. limiter = registry.rateLimiter(           │
  │       instanceName + ":" + ip,              │
  │       registry.getConfiguration(             │
  │           instanceName).orElseThrow())      │
  │                                             │
  │ 5. if limiter.acquirePermission()           │
  │       → chain.doFilter                      │
  │    else                                     │
  │       → errorWriter.write429(               │
  │             response, instanceName)         │
  │             ← serializes envelope +         │
  │               Retry-After                   │
  └────────────────────────────────────────────┘
```

### Where the new filter sits in F-AUTH's chain

The existing `SecurityFilterChain` from `SecurityConfig.java` is edited to
add **one** line: `.addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)`.
All current behaviour (CSRF disabled, STATELESS sessions, custom 401/403
handlers, scope-based authorization on the invoice endpoints) is preserved.

---

## Code Reuse Analysis

### Existing Components to Leverage

| Component                          | Location                                                         | How to Use                                                                                          |
| ---------------------------------- | ---------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `RateLimiterRegistry`              | auto-config from `resilience4j-spring-boot3`                     | Injected as a Spring bean. We call `registry.rateLimiter(name, config)` for per-IP synthesis.       |
| `SecurityConfig.securityFilterChain` | `adapter/security/SecurityConfig.java`                          | Edit the existing bean: add `.addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)`. |
| `ErrorResponseDto`                 | `adapter/web/dto/ErrorResponseDto.java`                          | Reused as the 429 body shape — `new ErrorResponseDto("RATE_LIMIT_EXCEEDED", "...")`.                  |
| `ObjectMapper`                     | Spring Boot auto-config                                          | Injected into `RateLimitErrorWriter` to serialize the envelope.                                     |
| `ApiSecurityProperties` precedent  | `adapter/security/ApiSecurityProperties.java`                    | Pattern for `@ConfigurationProperties` — not directly extended; `resilience4j.*` is the existing namespace. |
| `application.properties` precedent | `application.properties` lines 44-74 (existing `resilience4j.circuitbreaker.instances.*`) | Property style and naming convention copied for the new `resilience4j.ratelimiter.instances.*` block.   |
| `JwtTestSupport` precedent         | `src/test/java/.../testsupport/JwtTestSupport.java`              | Same pattern: a small test helper that synthesises real artefacts (here, headers + URIs) so integration tests exercise the real filter chain. |
| `NoOpKafkaTestConfig` precedent    | `src/test/java/.../config/NoOpKafkaTestConfig.java`              | New auth+rate-limit integration tests reuse it to skip Kafka context wiring.                          |
| `TaggedRateLimiterMetrics`         | `resilience4j-micrometer`                                        | Auto-binds to the global registry. We add a `MeterFilter` to keep per-IP synthetic names off it (AD-RLIM-2). |

### Integration Points

| System                             | Integration Method                                                                                                    |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| F-AUTH `SecurityFilterChain`       | One-line edit in `SecurityConfig.securityFilterChain` to `addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)`. |
| F-AUTH login + invoice endpoints   | Protected by the filter via path-pattern match (`auth-login`, `invoice-generate` groups).                              |
| F-OBSERVABILITY `/actuator/prometheus` | Three new meters auto-published by `resilience4j-micrometer` (one per static instance). Per-IP names filtered out via `RateLimiterMeterFilter`. |
| F-OBSERVABILITY structured logs    | Filter logs (DEBUG) the rate-limit decision per request via the existing Logback JSON encoder. The `clientIp` value goes to MDC + log fields only, **never** to a metric tag (AD-020). |
| `application.properties`           | New `resilience4j.ratelimiter.instances.{auth-login,invoice-generate,default}.*` block.                               |
| `application-test.properties`      | Generous defaults (limit-for-period=10000) so unrelated test suites do not get throttled.                              |

### Concerns to Mitigate

| Concern (from `.specs/codebase/CONCERNS.md` and ADRs)                       | Mitigation in this design                                                                                                                 |
| --------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| AD-020 cardinality budget (no IP/orderId/invoiceId on metric tags)          | `RateLimiterMeterFilter` rejects per-IP synthetic instance names. Statically-named instances (3 total) are the only ones that publish meters. |
| AD-017 SLI catalog frozen at four                                           | Rate-limit signals are queryable but **not** promoted to a fifth SLI. Documented under RLIM-OOS-5.                                          |
| AD-029 registered ≠ wired (F-OBSERVABILITY audit)                           | `RateLimitIntegrationTest` exercises the real filter chain. Bean wiring proven by a 429 response, not a unit test of the filter in isolation. |
| AD-024 in-process state is demo-only                                        | Per-IP `RateLimiter` instances live in the process-local registry. Documented under RLIM-OOS-3 (distributed limit is a production follow-up).  |
| AD-AUTH-5 / `ApiExceptionHandler` envelope shape                            | `RateLimitErrorWriter` writes the same `ErrorResponseDto(codigo, mensagem)` shape directly — `@ControllerAdvice` cannot intercept filter-level rejects. |

---

## Components

### `RateLimitFilter`

- **Purpose:** Intercept every HTTP request, look up the matching rate-limit
  group, resolve the client IP, acquire (or fail) a permit on the per-IP
  `RateLimiter` synthesised on-the-fly, and either continue the filter
  chain or serialise a 429 envelope.
- **Location:** `src/main/java/.../adapter/security/ratelimit/RateLimitFilter.java`
- **Interfaces** (extends `OncePerRequestFilter`):
  - `protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException`
- **Dependencies:** `RateLimitPolicy`, `RateLimiterRegistry`,
  `ClientIpResolver`, `RateLimitErrorWriter`. SLF4J `Logger` for DEBUG-level
  decision logs.
- **Reuses:** `OncePerRequestFilter` (Spring Web). No new abstraction.

### `RateLimitPolicy`

- **Purpose:** Single source of truth mapping an HTTP `(method, path)` to a
  rate-limit instance name. Returns `null` for paths that must be exempt
  (every `/actuator/**` request).
- **Location:** `src/main/java/.../adapter/security/ratelimit/RateLimitPolicy.java`
- **Interfaces:**
  - `String lookup(String method, String path)` — returns instance name or `null`
- **Dependencies:** `AntPathMatcher` (Spring Core).
- **Reuses:** the URI patterns already used in `SecurityConfig`
  (`/api/auth/login`, `/api/orders/generate-invoice`, `/api/pedido/gerarNotaFiscal`,
  `/actuator/**`).
- **Internal table** (frozen here, integers live in properties):

  | Method | Path pattern                                                          | Group              |
  | ------ | --------------------------------------------------------------------- | ------------------ |
  | POST   | `/api/auth/login`                                                     | `auth-login`       |
  | POST   | `/api/orders/generate-invoice`                                        | `invoice-generate` |
  | POST   | `/api/pedido/gerarNotaFiscal`                                         | `invoice-generate` |
  | *      | `/actuator/**`                                                        | (null — exempt)    |
  | *      | `/api/**` (catch-all, evaluated last)                                 | `default`          |
  | *      | (anything else — `OPTIONS` preflight, error pages, ...)               | (null — exempt)    |

### `ClientIpResolver`

- **Purpose:** Extract the client IP from an `HttpServletRequest`, preferring
  the first hop of `X-Forwarded-For` and falling back to
  `request.getRemoteAddr()`. Degenerate cases (`null`, empty,
  all-whitespace) return the literal string `"unknown"` so the filter never
  throws.
- **Location:** `src/main/java/.../adapter/security/ratelimit/ClientIpResolver.java`
- **Interfaces:**
  - `String resolve(HttpServletRequest request)`
- **Dependencies:** none.
- **Reuses:** no existing helper — we deliberately do not import any IP
  resolution library; the logic is ~15 lines and easier to read than a
  dependency.
- **Test coverage (unit):** `ClientIpResolverTest` — eight scenarios:
  single XFF hop, multi-hop XFF, missing XFF, empty XFF, whitespace XFF,
  null `getRemoteAddr()` fallback, IPv6 XFF, valid getRemoteAddr fallback.

### `RateLimitErrorWriter`

- **Purpose:** Serialise the 429 response — set status, `Content-Type:
  application/json`, write `ErrorResponseDto(codigo, mensagem)` body, set
  `Retry-After` header.
- **Location:** `src/main/java/.../adapter/security/ratelimit/RateLimitErrorWriter.java`
- **Interfaces:**
  - `void write429(HttpServletResponse response, String instanceName) throws IOException`
- **Dependencies:** `ObjectMapper`, `RateLimiterRegistry` (to read the
  configured `limit-refresh-period` for the `Retry-After` value).
- **Reuses:** `ErrorResponseDto` from `adapter/web/dto/`.

### `RateLimiterMeterFilter`

- **Purpose:** Block per-IP synthetic `RateLimiter` instance names from
  publishing Micrometer meters — enforces AD-020 (cardinality budget). Only
  the three statically-named instances (`auth-login`, `invoice-generate`,
  `default`) emit `resilience4j.ratelimiter.*` meters.
- **Location:** `src/main/java/.../adapter/security/ratelimit/RateLimiterMeterFilter.java`
- **Interfaces** (Micrometer `MeterFilter`):
  - `MeterFilterReply accept(Meter.Id id)`
- **Dependencies:** none beyond Micrometer.
- **Reuses:** existing `MeterFilter` registration pattern (none yet in
  codebase — this is the first `MeterFilter`; document the precedent in the
  class Javadoc for future cardinality guards).
- **Bean wiring:** registered as a `@Bean` returning `MeterRegistryCustomizer<MeterRegistry>`
  inside `RateLimitConfig`.

### `RateLimitConfig`

- **Purpose:** `@Configuration` that wires `RateLimitFilter`,
  `RateLimitPolicy`, `ClientIpResolver`, `RateLimitErrorWriter`,
  `RateLimiterMeterFilter` as Spring beans, and contributes the
  `MeterRegistryCustomizer` that installs the meter filter.
- **Location:** `src/main/java/.../adapter/security/ratelimit/RateLimitConfig.java`
- **Reuses:** `@Configuration` pattern mirroring `SecurityConfig`,
  `ApplicationBeanConfig`, `KafkaMessagingConfig`.

### `SecurityConfig` (edit, **not** new)

- **Change:** inject `RateLimitFilter` into the existing
  `securityFilterChain(...)` method and add **one** line:
  `.addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)`.
- **No other behaviour change.** CSRF, STATELESS, custom 401/403 entry
  point + access denied handler, scope rules — all preserved.

### `ApiExceptionHandler` (no change)

- **No edit.** Filter-level rejections never reach `DispatcherServlet`, so
  `@RestControllerAdvice` cannot intercept them. `RateLimitErrorWriter`
  emits the envelope itself.

---

## Data Models

### `ErrorResponseDto` (existing — no schema change)

```
record ErrorResponseDto(String codigo, String mensagem) {}
```

Reused as-is. The 429 body is `new ErrorResponseDto("RATE_LIMIT_EXCEEDED", "...")`.

### Properties contract

```properties
# application.properties — new block, sits next to the existing
# resilience4j.circuitbreaker.instances.* block (lines 44-74).

# auth-login: anti-brute-force, tight per-IP per-minute
resilience4j.ratelimiter.instances.auth-login.limit-for-period=5
resilience4j.ratelimiter.instances.auth-login.limit-refresh-period=60s
resilience4j.ratelimiter.instances.auth-login.timeout-duration=0
resilience4j.ratelimiter.instances.auth-login.register-health-indicator=false

# invoice-generate: business throughput, per-IP per-minute
resilience4j.ratelimiter.instances.invoice-generate.limit-for-period=30
resilience4j.ratelimiter.instances.invoice-generate.limit-refresh-period=60s
resilience4j.ratelimiter.instances.invoice-generate.timeout-duration=0
resilience4j.ratelimiter.instances.invoice-generate.register-health-indicator=false

# default: catch-all for any future /api/** endpoint
resilience4j.ratelimiter.instances.default.limit-for-period=60
resilience4j.ratelimiter.instances.default.limit-refresh-period=60s
resilience4j.ratelimiter.instances.default.timeout-duration=0
resilience4j.ratelimiter.instances.default.register-health-indicator=false
```

```properties
# application-test.properties — generous defaults so unrelated suites
# (InvoiceController, MetricsIntegration, etc.) never falsely throttle.
resilience4j.ratelimiter.instances.auth-login.limit-for-period=10000
resilience4j.ratelimiter.instances.invoice-generate.limit-for-period=10000
resilience4j.ratelimiter.instances.default.limit-for-period=10000
```

`RateLimitIntegrationTest` overrides these via `@TestPropertySource` to tiny
values (e.g. `auth-login.limit-for-period=3`) so the trip is observable.

---

## Filter chain layout (post-F-RATELIMIT)

```
HttpSecurity
  .csrf(disable)
  .sessionManagement(SessionCreationPolicy.STATELESS)
  .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)  ← NEW
  .exceptionHandling(eh -> eh
      .authenticationEntryPoint(apiBearerAuthenticationEntryPoint)
      .accessDeniedHandler(apiBearerAccessDeniedHandler))
  .authorizeHttpRequests(a -> a
      .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
      .requestMatchers("/actuator/health", "/actuator/health/**",
                       "/actuator/info", "/actuator/prometheus").permitAll()
      .requestMatchers(HttpMethod.POST,
          "/api/orders/generate-invoice",
          "/api/pedido/gerarNotaFiscal").hasAuthority("SCOPE_invoice:write")
      .anyRequest().authenticated())
  .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwtDecoder))
      .authenticationEntryPoint(apiBearerAuthenticationEntryPoint)
      .accessDeniedHandler(apiBearerAccessDeniedHandler));
```

---

## Error Handling Strategy

| Error Scenario                                              | Handling                                                                                                          | User Impact                                                                                       |
| ----------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| Permit unavailable for `(group, ip)`                        | `RateLimitErrorWriter.write429` → 429 + envelope + `Retry-After`                                                  | `HTTP 429`, JSON body `{"codigo":"RATE_LIMIT_EXCEEDED","mensagem":"..."}`, `Retry-After: <sec>`. |
| `X-Forwarded-For` malformed (empty / whitespace)            | `ClientIpResolver` falls back to `getRemoteAddr()`                                                                | No user impact; filter does **not** throw.                                                        |
| `getRemoteAddr()` returns `null` (degenerate test setup)    | `ClientIpResolver` returns literal `"unknown"`                                                                    | All such requests share one bucket — safe failure mode (over-throttle, never under-throttle).     |
| Path matches no policy entry                                | Filter passes through (no permit consumed)                                                                        | No user impact; behaviour unchanged.                                                              |
| `RateLimiterRegistry.getConfiguration(group)` empty (misconfigured) | Filter logs WARN, falls through without limiting that request                                                  | Misconfig is visible in logs; abuse path is briefly unprotected (operator action required).       |
| Resilience4j `RequestNotPermitted` (theoretical — only on `timeout > 0`) | Caught and converted to 429 by the filter (defence-in-depth)                                                  | Same 429 envelope.                                                                                |
| `OPTIONS` preflight                                         | Pre-policy short-circuit: filter inspects `request.getMethod()`; OPTIONS bypasses lookup                          | No permit consumed (CORS preflight is not user-driven).                                           |

---

## Test strategy by layer

| Test layer                  | Pattern                                                       | Files                                                                                            |
| --------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| Unit (`ClientIpResolver`)   | Plain JUnit; `MockHttpServletRequest` from `spring-test`      | `ClientIpResolverTest` — 8 scenarios (XFF variants + getRemoteAddr fallback + degenerate)        |
| Unit (`RateLimitPolicy`)    | Plain JUnit                                                   | `RateLimitPolicyTest` — verifies the 5-row table maps as documented (auth-login, invoice-generate (canonical + alias), actuator exempt, default catch-all) |
| Integration (rate-limit trip) | `@SpringBootTest` + `MockMvc` + `@TestPropertySource` (tiny limits) + distinct synthetic IPs per method | `RateLimitIntegrationTest` — ~6 tests: login trips at 4th, invoice trips at Nth, per-IP isolation, actuator never throttled, Retry-After header present, alias shares bucket |
| Integration (Prometheus scrape) | Extend `MetricsIntegrationTest` or new mini-test           | One assertion that `/actuator/prometheus` contains `resilience4j_ratelimiter_available_permissions{name="auth-login"...}` after a request through the auth-login group. |
| Manual smoke                | `for i in {1..6}; do curl ... ; done` (Success Criterion 2)   | n/a                                                                                              |
| Newman / Postman            | Extend the F-POSTMAN collection with one `RATE_LIMIT_EXCEEDED` request | `docs/postman/invoice-generator.postman_collection.json` (new request after the existing ones)   |

---

## Risks and mitigations

| Risk                                                                                          | Mitigation                                                                                                                                                                                  |
| --------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Per-IP synthesised `RateLimiter` instances explode Micrometer cardinality (violates AD-020)   | `RateLimiterMeterFilter` rejects per-IP synthetic names. Integration test asserts `/actuator/prometheus` contains only the three statically-named instances after exercising the chain.    |
| Existing integration tests get falsely throttled                                              | `application-test.properties` sets every limit to `10000`. `RateLimitIntegrationTest` is the only suite that overrides them down via `@TestPropertySource`.                                  |
| Adding `addFilterBefore` to `SecurityFilterChain` breaks F-AUTH or F-OBSERVABILITY tests      | The 103-test gate (`./mvnw verify`) is run after T2. The filter passes through on the test-profile generous defaults — F-AUTH and F-OBSERVABILITY tests never trip a limit.                  |
| `BearerTokenAuthenticationFilter` is not the Spring Security filter name on Boot 3.5          | Verified against Spring Security 6 source (`spring-security-oauth2-resource-server` 6.2+ exposes `BearerTokenAuthenticationFilter`). Documented here so the reviewer can grep & confirm.   |
| Memory growth: one `RateLimiter` per unique IP per group, no TTL                              | Acceptable for demo (single-process, no production tenant base). Documented under RLIM-OOS-3 as a follow-up; production needs distributed store + TTL eviction.                              |
| `Retry-After` integer-seconds is not the *exact* wait time                                    | AD-RLIM-4 records the conservative-ceiling decision and the rationale.                                                                                                                       |
| A new endpoint added later is **not** under `/api/**` and falls through unthrottled           | `RateLimitPolicy.lookup` is documented as "non-`/api/**` paths are exempt by design" (only actuator + future operational endpoints). A reviewer note in the policy Javadoc reminds future authors. |
| Filter logs leak the client IP into structured logs at INFO                                   | Filter logs at **DEBUG** only; INFO-level F-OBSERVABILITY access logs already include `clientIp` via the existing MDC pipeline. No new log field, no new PII surface.                       |
| `RequestNotPermitted` (Resilience4j) thrown unexpectedly somewhere downstream                 | `@ExceptionHandler(RequestNotPermitted.class)` in `ApiExceptionHandler` as a defence-in-depth — also emits `RATE_LIMIT_EXCEEDED` + 429. Belt-and-braces with the filter's primary path.    |

---

## Tech Decisions Recap

| Decision                                  | Choice                                              | Rationale (one-liner)                                                                  |
| ----------------------------------------- | --------------------------------------------------- | -------------------------------------------------------------------------------------- |
| Wire-in seam                              | `OncePerRequestFilter`, before `BearerTokenAuthenticationFilter` | Anti-brute-force before any auth cost; central path-to-instance mapping. (AD-RLIM-1)   |
| Per-IP isolation mechanism                | Synthesise per-`(group, ip)` `RateLimiter` on-the-fly | No first-class key extractor in Resilience4j; registry lookup is thread-safe + cheap. (AD-RLIM-2) |
| Cardinality guard                         | `MeterFilter` rejects per-IP synthetic instance names | Preserves AD-020 cardinality budget. (AD-RLIM-2)                                       |
| `codigo` constant                          | Literal string `"RATE_LIMIT_EXCEEDED"`              | Matches F-AUTH precedent; `RejectionCode` enum stays invoice-domain-only. (AD-RLIM-3)  |
| `Retry-After` value                       | `ceil(limit-refresh-period)` in seconds              | Resilience4j fixed-window does not expose precise wait; conservative ceiling is honest. (AD-RLIM-4) |
| Test pattern                              | Real filter chain + tiny `@TestPropertySource` limits + distinct synthetic IPs | Same "real chain proves wiring" lesson as AD-029 / AD-AUTH-4. (AD-RLIM-5)              |
| Error envelope                            | `ErrorResponseDto(codigo, mensagem)` (existing DTO)  | Contract consistency; one envelope across the whole API.                               |
| Algorithm                                  | Resilience4j default fixed-window                    | Out of scope to tune; library default is fine for the contract. (RLIM-OOS-7)           |
| Distributed limit                          | **Out** — in-process registry only                   | Demo-grade, per AD-024 precedent. Captured under RLIM-OOS-3.                            |

---

## Open questions for tasks.md

- **None.** The decisions above fully determine the implementation. The
  task breakdown is purely about ordering the slices for atomic commits and
  reviewable diffs.
