# F-RATELIMIT Tasks

**Spec:** [`spec.md`](spec.md) · **Design:** [`design.md`](design.md)
**Status:** Draft (2026-05-24)
**Granularity policy:** 5 consolidated vertical slices, per user preference
(see [[feedback_task-granularity]]). Each slice is one atomic commit.

---

## Execution Plan

```
T1 (scaffolding: properties + RateLimitConfig + Policy + Resolver
    + ErrorWriter + unit tests — no wire-in yet)
  │
  └──→ T2 (RateLimitFilter + wire into SecurityFilterChain
           + test-profile generous limits + RateLimitIntegrationTest)
            │
            └──→ T3 (RateLimiterMeterFilter cardinality guard
                     + scrape assertion + RequestNotPermitted advice)
                       │
                       └──→ T4 (Postman collection: add 429 request
                                + Newman green end-to-end)
                                  │
                                  └──→ T5 (docs + ROADMAP M5 + STATE AD-033)
```

- T1 lands the inert scaffolding. No HTTP behaviour change, existing 103
  tests stay green — proves the new beans and properties compile clean.
- T2 wires the filter into the chain and proves the 429 path end-to-end
  through a real `MockMvc` + filter-chain integration test. Most of the
  feature's behaviour ships here.
- T3 closes two loose ends in one commit: the cardinality guard
  (`MeterFilter` rejecting per-IP synthetic names per AD-RLIM-2 + AD-020)
  and the defence-in-depth `@ExceptionHandler(RequestNotPermitted)` in
  `ApiExceptionHandler`. Both depend on the filter being live (T2).
- T4 extends the Postman collection so Newman regression covers the new
  contract.
- T5 closes the docs/roadmap/STATE loop.

Sequential. No `[P]` — each slice's gate (`./mvnw verify` or `newman run`)
is a serial gate that the next slice depends on. No shared mutable test
state warrants parallel execution.

---

## Task Breakdown

### T1: Scaffolding — properties + RateLimitConfig + Policy + Resolver + ErrorWriter + unit tests

**What:** Add the `resilience4j.ratelimiter.instances.{auth-login, invoice-generate,
default}.*` block to `application.properties`. Create `RateLimitConfig`
(`@Configuration`) declaring `ClientIpResolver`, `RateLimitPolicy`,
`RateLimitErrorWriter` beans (the filter itself comes in T2 so the
scaffolding slice does not change HTTP behaviour). Create
`ClientIpResolver` (X-Forwarded-For first hop + `getRemoteAddr()` fallback
+ `"unknown"` sentinel) with a unit test covering the 8 scenarios listed
in the design. Create `RateLimitPolicy` with the 5-row table from
`design.md`§Components and a unit test asserting every row maps as
documented (including `/actuator/**` → `null`, alias shares
`invoice-generate`, `/api/**` catch-all → `default`). Create
`RateLimitErrorWriter` (serialise `ErrorResponseDto("RATE_LIMIT_EXCEEDED", ...)`
+ `Retry-After: ceil(refresh-period)` header). No `RateLimitFilter` yet, no
wire-in to `SecurityConfig`. The existing 103 fast tests must stay green.

**Where:**

- `src/main/java/.../adapter/security/ratelimit/RateLimitConfig.java` (new)
- `src/main/java/.../adapter/security/ratelimit/RateLimitPolicy.java` (new)
- `src/main/java/.../adapter/security/ratelimit/ClientIpResolver.java` (new)
- `src/main/java/.../adapter/security/ratelimit/RateLimitErrorWriter.java` (new)
- `src/main/resources/application.properties` (add ratelimiter block)
- `src/test/java/.../adapter/security/ratelimit/ClientIpResolverTest.java` (new)
- `src/test/java/.../adapter/security/ratelimit/RateLimitPolicyTest.java` (new)

**Depends on:** none.

**Reuses:** `@Configuration` pattern from `SecurityConfig` /
`ApplicationBeanConfig` / `KafkaMessagingConfig`; `resilience4j.circuitbreaker.instances.*`
property naming convention from `application.properties` lines 44-74;
`ErrorResponseDto` from `adapter/web/dto/`; `AntPathMatcher` (Spring Core)
for `/actuator/**` + `/api/**` matching.

**Requirements covered:** preparation for RLIM-09, RLIM-10, RLIM-11 (config);
RLIM-16, RLIM-17, RLIM-19 (resolver behaviour); RLIM-20, RLIM-21, RLIM-22
(writer envelope) — actually-tested-end-to-end status flips in T2.

**Done when:**

- [ ] `./mvnw verify` green; 103 existing + 2 new test classes (~16 new
      assertions across `ClientIpResolverTest` + `RateLimitPolicyTest`).
- [ ] Spring Boot context loads with `RateLimitConfig` active; no HTTP
      behaviour change for any existing endpoint.
- [ ] `resilience4j.ratelimiter.instances.auth-login.limit-for-period=5`
      etc. all present in `application.properties`.
- [ ] `ClientIpResolverTest` asserts: single XFF hop, multi-hop XFF, missing
      XFF (`getRemoteAddr` fallback), empty XFF, whitespace XFF, null
      `getRemoteAddr` (`"unknown"` sentinel), IPv6 XFF, normal `getRemoteAddr`.
- [ ] `RateLimitPolicyTest` asserts every row of the design table maps as
      documented (5 rows × at least one assertion each).
- [ ] No new entries in `RejectionCode` enum (AD-RLIM-3).

**Tests:** unit (resolver + policy).
**Gate:** `./mvnw verify`.

**Commit:** `feat(ratelimit): scaffold config, policy, IP resolver, error writer + unit tests (T1)`

---

### T2: RateLimitFilter + wire-in + test-profile generous limits + RateLimitIntegrationTest

**What:** Create `RateLimitFilter` (`OncePerRequestFilter`) implementing the
flow from `design.md`§Architecture Overview: policy lookup → exempt /
short-circuit → resolve IP → synthesise per-`(group, ip)` `RateLimiter`
via `registry.rateLimiter(name, registry.getConfiguration(group).orElseThrow())`
→ `acquirePermission()` → continue or `errorWriter.write429()`. Edit
`SecurityConfig.securityFilterChain` to inject `RateLimitFilter` and add
one line: `.addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)`.
Add `src/test/resources/application-test.properties` overrides setting
`limit-for-period=10000` on every group so the existing 103 tests cannot
trip the limiter (RLIM-27). Add `RateLimitIntegrationTest`
(`@SpringBootTest` + `MockMvc` + `@TestPropertySource` overriding
`auth-login.limit-for-period=3`, `invoice-generate.limit-for-period=4`)
with ~6 tests covering: login trips at 4th request from same XFF IP
(RLIM-01, RLIM-02, RLIM-20, RLIM-21, RLIM-22), per-IP isolation between
two `X-Forwarded-For` values (RLIM-04, RLIM-18), `/actuator/health`
hammered 30× returns 200 every time (RLIM-12), invoice canonical + legacy
alias share `invoice-generate` bucket (RLIM-06, RLIM-07, RLIM-08), `OPTIONS`
preflight passes through without consuming a permit (edge case), malformed
`X-Forwarded-For` falls back without throwing (RLIM-19).

**Where:**

- `src/main/java/.../adapter/security/ratelimit/RateLimitFilter.java` (new)
- `src/main/java/.../adapter/security/SecurityConfig.java` (edit — inject
  filter + one `addFilterBefore` line)
- `src/test/resources/application-test.properties` (new or extend — add
  the three generous overrides; preserve any existing keys like
  `app.security.jwt.secret`, `app.messaging.kafka.enabled=false`,
  `spring.kafka.bootstrap-servers`)
- `src/test/java/.../adapter/security/ratelimit/RateLimitIntegrationTest.java` (new)

**Depends on:** T1.

**Reuses:** the F-AUTH `SecurityConfig.securityFilterChain` bean; `JwtTestSupport`
helper (already in `src/test/java/.../testsupport/`) so the
`invoice-generate` test exercises a token-bearing request through the full
chain; `NoOpKafkaTestConfig` to skip Kafka context wiring; `MockMvc`
+ `@SpringBootTest` pattern from `AuthControllerIntegrationTest` /
`SecurityIntegrationTest`.

**Requirements covered:** RLIM-01..RLIM-08, RLIM-12..RLIM-14 (functional
behaviour), RLIM-16..RLIM-19 (per-IP, fallback), RLIM-20..RLIM-22 (envelope),
RLIM-27 (existing tests stay green via generous test defaults), RLIM-28
(new integration coverage).

**Done when:**

- [ ] `./mvnw verify` green; 103 existing tests stay green; ~6 new
      integration tests in `RateLimitIntegrationTest` pass. Total fast test
      count grows from 103 to ~109 (+ the 2 unit classes from T1 = ~111).
- [ ] Manual: `for i in {1..6}; do curl -s -o /dev/null -w '%{http_code}\n'
      -X POST http://localhost:8080/api/auth/login -H 'Content-Type:
      application/json' -d '{"username":"demo","password":"demo123"}'; done`
      returns five `200`s then one `429` (success criterion 2).
- [ ] Manual: the 6th response body parses as JSON with
      `codigo === "RATE_LIMIT_EXCEEDED"`; response header `Retry-After` is
      a positive integer.
- [ ] No usage of `@WithMockUser` or `addFilters=false` in
      `RateLimitIntegrationTest` (real chain only, per AD-RLIM-5).
- [ ] `SecurityConfig.securityFilterChain` change is exactly one
      `.addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)`
      line — no other behaviour change.
- [ ] Each `RateLimitIntegrationTest` method uses a **distinct synthetic
      IP** in `X-Forwarded-For` (`10.0.0.1`, `10.0.0.2`, …) so bucket state
      cannot leak between tests.

**Tests:** integration.
**Gate:** `./mvnw verify`.

**Commit:** `feat(ratelimit): RateLimitFilter wired into SecurityFilterChain + 6 ITs (T2)`

---

### T3: Cardinality guard + defense-in-depth

**What:** Create `RateLimiterMeterFilter` (Micrometer `MeterFilter`)
that rejects any `resilience4j.ratelimiter.*` meter whose `name` tag does
not match the three statically-named instances (`auth-login`,
`invoice-generate`, `default`). Register it via a
`MeterRegistryCustomizer<MeterRegistry>` `@Bean` inside `RateLimitConfig`.
Add `@ExceptionHandler(RequestNotPermitted.class)` to `ApiExceptionHandler`
that returns the same 429 envelope (defence-in-depth — the primary path is
the filter, but any downstream code that ever uses `@RateLimiter`
annotations later will get the same contract). Add
`RateLimitMetricsIntegrationTest` (small class, 2 tests) asserting:
(a) after a request through the `auth-login` group, `GET
/actuator/prometheus` contains a line matching
`resilience4j_ratelimiter_available_permissions{[^}]*name="auth-login"[^}]*}`;
(b) after multiple requests from many synthetic IPs, the scrape contains
**only** the three statically-named instances — no `name="auth-login:10.0.0.1"`
or similar per-IP synthetic names leak into the meter set (RLIM-26).

**Where:**

- `src/main/java/.../adapter/security/ratelimit/RateLimiterMeterFilter.java` (new)
- `src/main/java/.../adapter/security/ratelimit/RateLimitConfig.java` (edit —
  add `MeterRegistryCustomizer` `@Bean` returning a customizer that
  registers `RateLimiterMeterFilter`)
- `src/main/java/.../adapter/web/ApiExceptionHandler.java` (edit — add
  `@ExceptionHandler(RequestNotPermitted.class)` returning 429 + envelope)
- `src/test/java/.../adapter/security/ratelimit/RateLimitMetricsIntegrationTest.java` (new)

**Depends on:** T2.

**Reuses:** the existing `MetricsIntegrationTest` scrape pattern
(`mockMvc.perform(get("/actuator/prometheus"))` → assert on response body
content) as the template for the two assertions; `ApiExceptionHandler`'s
existing structure (mirrors the `InvalidCredentialsException` handler from
F-AUTH).

**Requirements covered:** RLIM-24, RLIM-25, RLIM-26 (meters exposed +
cardinality guard). The `RequestNotPermitted` advice is defence-in-depth
beyond the spec — captured as design note AD-RLIM-1 trade-off.

**Done when:**

- [ ] `./mvnw verify` green; ~111 + 2 = ~113 fast tests pass.
- [ ] `GET /actuator/prometheus` after a request through `/api/auth/login`
      contains exactly one
      `resilience4j_ratelimiter_available_permissions{name="auth-login",...}`
      line (no per-IP variants).
- [ ] `RateLimitMetricsIntegrationTest` issues requests from 3+ distinct
      synthetic IPs and asserts the scrape still has only one line per
      statically-named instance.
- [ ] `ApiExceptionHandler.handleRequestNotPermitted` returns 429 with
      `ErrorResponseDto("RATE_LIMIT_EXCEEDED", "...")`.

**Tests:** integration.
**Gate:** `./mvnw verify`.

**Commit:** `feat(ratelimit): meter cardinality guard + RequestNotPermitted advice (T3)`

---

### T4: Postman collection update — `RATE_LIMIT_EXCEEDED` request + Newman green

**What:** Add a new request to
`docs/postman/invoice-generator.postman_collection.json` (after the
existing 6-8 requests). Name: `07 - Rate Limit — 429 envelope on login`.
Pre-request script: issues 5 synchronous logins to `/api/auth/login` so
the 6th (the request itself) is the one that trips. Assertions:
`pm.response.code === 429`, parsed body `codigo === "RATE_LIMIT_EXCEEDED"`,
`pm.response.headers.has("Retry-After")`, `Retry-After` is a positive
integer. Use a header `X-Forwarded-For: 10.99.0.1` so this request's
bucket does not collide with the auto-login flow's bucket (which uses no
XFF and therefore the local IP). Update the F-POSTMAN section of
`docs/postman/README.md` if any docs ship there. Verify Newman runs
green end-to-end against `docker compose up -d kafka` + local app
(per the recipe in `.specs/codebase/TESTING.md`).

**Where:**

- `docs/postman/invoice-generator.postman_collection.json` (edit — add 1
  request)
- `docs/postman/README.md` (edit only if the file documents the request
  list; otherwise leave untouched)

**Depends on:** T3.

**Reuses:** the existing F-POSTMAN auto-login Pre-request script (the
collection variable `accessToken`); the `baseUrl` collection variable;
`.specs/codebase/TESTING.md` documented Newman run recipe (the
F-AUTH-T6 verified run was 8 requests / 24 assertions; this slice
brings it to 9 requests / 27-28 assertions).

**Requirements covered:** RLIM-30.

**Done when:**

- [ ] `npx newman run docs/postman/invoice-generator.postman_collection.json`
      green end-to-end with the live app reachable on `localhost:8080` and
      `localhost:29092` (Kafka). Report 9 requests, ≥ 27 assertions, 0
      failures.
- [ ] The new request actually returns 429 in the run (verify by inspecting
      Newman output for the 6th login attempt).
- [ ] The auto-login flow for the rest of the collection is not affected
      (the new request uses a distinct `X-Forwarded-For`).

**Tests:** integration (Newman is the gate here, not Maven).
**Gate:** `npx newman run docs/postman/invoice-generator.postman_collection.json`.

**Commit:** `test(ratelimit): postman 429 request + newman regression (T4)`

---

### T5: Docs + ROADMAP M5 + STATE AD-033

**What:** Wrap up. Add a "Rate limiting" section to `docs/business-rules.md`
(after the F-AUTH section) with the three-group table, the per-IP key, the
`/actuator/**` exemption rationale, and the 429 envelope contract
(RLIM-31). Add a "Rate-limit signals" sub-section to `docs/observability.md`
listing the three auto-published meters
(`resilience4j_ratelimiter_available_permissions{name=...}` × 3) and one
sample Prometheus query (RLIM-32). Update `CLAUDE.md` with a new section
under the F-AUTH defect-status block describing the filter placement, the
three instance names, the `/actuator/**` exemption, and the 429 contract
(RLIM-36). Update `README.md` and `README-CHALLENGE.md` to mention
F-RATELIMIT (RLIM-33). Introduce **M5 — Abuse protection** in
`ROADMAP.md` with F-RATELIMIT as its single feature, status COMPLETE
(RLIM-35). Add `AD-033` to `STATE.md` recording the four key design
decisions: library (`resilience4j-ratelimiter`), coverage (per-endpoint
groups + actuator exempt), key (per-IP w/ `X-Forwarded-For` fallback),
implementation seam (`Filter` in `SecurityFilterChain`), error envelope
(`{codigo, mensagem}` + `Retry-After`) (RLIM-34). Update STATE header's
"Current Work" line. Move the "Rate limiting on `/api/auth/login`" item
from ROADMAP Future Considerations to closed (with a pointer to F-RATELIMIT).

**Where:**

- `docs/business-rules.md` (new section)
- `docs/observability.md` (new sub-section)
- `CLAUDE.md` (new sub-section)
- `README.md` (add row / flip status / add pointer — exact placement
  matches F-AUTH's edit in commit `a0c4b95`)
- `README-CHALLENGE.md` (add row / pointer)
- `.specs/project/ROADMAP.md` (new M5 milestone + F-RATELIMIT entry +
  remove the item from Future Considerations or strike-through it like
  `[x]`)
- `.specs/project/STATE.md` (header update, AD-033, Quick Tasks Completed
  table extended)
- `.specs/codebase/TESTING.md` (extend Newman last-run table to the new
  9 requests / ≥ 27 assertions; add `RateLimitIntegrationTest` +
  `RateLimitMetricsIntegrationTest` + `ClientIpResolverTest` +
  `RateLimitPolicyTest` to the class-by-class table with F-RATELIMIT
  attribution)
- `.specs/codebase/CONCERNS.md` (mention the in-process limit non-durability
  trade-off if the file already tracks similar AD-024 / AD-027 follow-ups —
  otherwise leave untouched)

**Depends on:** T4 (the Newman last-run table needs the new request count).

**Reuses:** the F-AUTH T6 docs-update template (commit `a0c4b95`); the
AD-NNN numbering convention in `STATE.md`; the milestone shape of M3 / M4
in `ROADMAP.md`.

**Requirements covered:** RLIM-31, RLIM-32, RLIM-33, RLIM-34, RLIM-35,
RLIM-36; success criteria items 6, 7, 8.

**Done when:**

- [x] *to-check by user* — `grep -r "F-RATELIMIT" docs/ .specs/ README*.md
      CLAUDE.md` returns hits in every file listed in `Where`.
- [x] *to-check* — ROADMAP shows M5 with F-RATELIMIT ✅, and the
      `Rate limiting on /api/auth/login (Bucket4j or Spring rate-limit)`
      line under Future Considerations is closed (strike-through or
      `[x]`).
- [x] *to-check* — STATE shows AD-033 with the five design decisions
      recorded (library, coverage, key, seam, envelope).
- [x] *to-check* — TESTING.md last-run table matches the T4 Newman result.
- [x] *to-check* — No remaining `Pending` rows in the requirement
      traceability table in `spec.md` for RLIM-01..RLIM-36 (each should
      flip to `Verified` after T4 / T5).

**Tests:** none new (docs).
**Gate:** `./mvnw verify` (defence-in-depth: docs edits shouldn't break
the build, but the gate is fast and we never ship a slice without it) +
`npx newman run docs/postman/invoice-generator.postman_collection.json`.

**Commit:** `docs(ratelimit): close F-RATELIMIT — M5 ROADMAP + AD-033 + ops docs (T5)`

---

## Parallel Execution Map

Sequential. No `[P]` tasks — each slice's gate (`./mvnw verify` or `newman
run`) is the dependency that gates the next slice.

```
T1 ──→ T2 ──→ T3 ──→ T4 ──→ T5
```

**Why no parallel:**

- T1 → T2: T2 imports `RateLimitConfig`, `RateLimitPolicy`,
  `ClientIpResolver`, `RateLimitErrorWriter` from T1.
- T2 → T3: T3's `RateLimitMetricsIntegrationTest` scrapes meters created
  by exercising the filter from T2; the meter filter wiring needs the
  `RateLimitConfig` bean already in place.
- T3 → T4: T4's Newman run is the end-to-end proof. Running it before T3's
  meter guard is shipped would leave the cardinality risk in production
  even briefly.
- T4 → T5: T5's docs reference T4's verified Newman counts.

---

## Task Granularity Check

| Task                                                                                         | Scope                                                                            | Status      |
| -------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- | ----------- |
| T1: scaffolding (4 prod classes + properties + 2 unit tests)                                 | 1 package, 1 config namespace, 2 unit tests — all inert until T2 wires the filter | ✅ Granular |
| T2: filter + wire-in + test-profile + integration test                                        | 1 new prod class + 1 edited class (1-line edit) + 1 new test-resource + 1 new integration test class | ✅ Granular |
| T3: meter guard + RequestNotPermitted advice + scrape test                                   | 1 new prod class + 2 small edits + 1 new test class                              | ✅ Granular |
| T4: Postman collection update                                                                | 1 JSON edit + Newman regression                                                 | ✅ Granular |
| T5: docs + ROADMAP + STATE + TESTING.md                                                       | ~8 doc files (template established by F-AUTH T6)                                 | ✅ Granular |

All slices are vertical (cohesive deliverable per slice) per the user's
task-granularity preference. None exceed F-AUTH-T6's scope (which also
touched 8 files in one commit).

---

## Diagram-Definition Cross-Check

| Task | `Depends on` (task body) | Diagram shows | Status |
| ---- | ------------------------ | ------------- | ------ |
| T1   | none                     | start node    | ✅ Match |
| T2   | T1                       | T1 → T2       | ✅ Match |
| T3   | T2                       | T2 → T3       | ✅ Match |
| T4   | T3                       | T3 → T4       | ✅ Match |
| T5   | T4                       | T4 → T5       | ✅ Match |

Linear chain; no branches; cross-check trivial. Pass.

---

## Test Co-location Validation

The project does not maintain a formal `TESTING.md` Test Coverage Matrix
with required test types per layer (the file is a class-by-class
inventory + Newman recipe per L-004, not a per-layer coverage matrix).
Applying the spirit of the rule — every slice that introduces new prod
code includes its tests in the same slice — gives:

| Task | Code layer created/modified                            | Tests included in the same slice               | Status     |
| ---- | ------------------------------------------------------ | ---------------------------------------------- | ---------- |
| T1   | `RateLimitConfig`, `RateLimitPolicy`, `ClientIpResolver`, `RateLimitErrorWriter` (adapter layer, pure logic in resolver + policy) | `ClientIpResolverTest` + `RateLimitPolicyTest` | ✅ Co-located |
| T2   | `RateLimitFilter` (adapter, HTTP filter); `SecurityConfig` edit | `RateLimitIntegrationTest` (real chain)        | ✅ Co-located |
| T3   | `RateLimiterMeterFilter` (adapter, metrics customizer); `ApiExceptionHandler` edit | `RateLimitMetricsIntegrationTest` (scrape assertion) | ✅ Co-located |
| T4   | Postman collection (test asset, not prod code)         | Newman regression in the same slice            | ✅ Co-located |
| T5   | Docs only                                              | n/a (no prod code modified)                    | ✅ N/A     |

No `Tests: none` deferrals. Every code-touching slice ships its own
verification.

---

## Tools per task

| Task | Tools                                                                                              |
| ---- | -------------------------------------------------------------------------------------------------- |
| T1   | Write/Edit (file ops); `./mvnw verify` via Bash. No MCP. No skill.                                  |
| T2   | Write/Edit; `./mvnw verify`; one `curl` smoke check from the Done-When list.                       |
| T3   | Write/Edit; `./mvnw verify`.                                                                       |
| T4   | Edit (JSON); `npx newman run docs/postman/invoice-generator.postman_collection.json` via Bash.     |
| T5   | Write/Edit (docs); `./mvnw verify` + `npx newman run …` as the closing gate.                       |

---

## Tips

- **Each commit is one slice.** Don't bundle T1+T2 even though T1 alone
  doesn't change behaviour — the inert-scaffolding diff is easier to
  review on its own.
- **T2 is the "moment of truth" slice.** That's where the filter wires
  into the real `SecurityFilterChain` and the integration test proves the
  contract. Take extra care with the `@TestPropertySource` integers and
  the synthetic XFF IPs — bucket leakage between tests is the most
  likely failure mode.
- **Don't add `RATE_LIMIT_EXCEEDED` to `RejectionCode`.** Use the literal
  string in `RateLimitErrorWriter` and in the new `ApiExceptionHandler`
  advice (AD-RLIM-3). The `RejectionCode` enum is bound to the
  `invoice.rejected{reason}` business counter — see AD-020.
- **Don't add a per-IP tag to any new Micrometer meter.** The whole point
  of `RateLimiterMeterFilter` in T3 is to keep IP cardinality off the
  metric plane. If you find yourself reaching for `Tags.of("clientIp", ip)`,
  stop and re-read AD-020 + AD-RLIM-2.
- **`SecurityConfig.securityFilterChain` change is exactly one line.** If
  you find yourself rewriting the chain, you're doing too much in T2.
  The line is:
  `.addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)`.
