# F-AUTH Tasks

**Spec:** [`spec.md`](spec.md) · **Design:** [`design.md`](design.md)
**Status:** In progress
**Granularity policy:** 6 consolidated vertical slices, per user preference
(see [[feedback_task-granularity]]). Each slice is one atomic commit.

---

## Execution Plan

```
T1 (security foundation: deps + JWT beans + open SecurityFilterChain)
  │
  ├──→ T2 (login endpoint: AuthController + JwtIssuer + InMemoryUserStore + errors)
  │
  ├──→ T3 (lock down invoice endpoints: oauth2ResourceServer + scope enforcement
  │        + custom 401/403 entrypoints)
  │           │
  │           └──→ T4 (update existing 88 tests: JwtTestSupport + token on 4 ITs)
  │                       │
  │                       └──→ T5 (new auth ITs: login + filter chain)
  │
  └──→ T6 (docs + Postman + ROADMAP M4 + STATE AD-032)
```

- T1 is the dependency root (everything downstream needs the JWT beans).
- T2 and T3 are split so the "issuer works" diff and the "decoder + filter chain"
  diff are separately reviewable. T3 strictly depends on T2 (the filter chain rejects
  unauthenticated invoice calls; we need a working login first or the tests are
  unbootable).
- T4 is the only slice that touches existing test files; isolating it keeps the diff
  obviously about "existing tests now attach a token".
- T5 adds the new auth coverage.
- T6 closes the loop (docs + Postman + roadmap + ADR).

---

## Task Breakdown

### T1: Security foundation — Maven deps + properties + JWT beans + open filter chain

**What:** Add the two Spring Security dependencies. Introduce
`ApiSecurityProperties` (`@ConfigurationProperties("app.security.jwt")`) with
`secret`, `issuer`, `expiry` fields. Create `SecurityConfig` with a `SecretKey` bean
derived from the secret, a `NimbusJwtEncoder` bean, a `NimbusJwtDecoder` bean
(HS256), a `BCryptPasswordEncoder` bean, and a permissive `SecurityFilterChain` that
allows every request. **No endpoints are locked down yet** — this slice exists to
prove the bean wiring compiles and the existing tests still pass with Spring
Security on the classpath.

**Where:**

- `pom.xml` (add `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`)
- `src/main/java/.../adapter/security/SecurityConfig.java` (new)
- `src/main/java/.../adapter/security/ApiSecurityProperties.java` (new)
- `src/main/resources/application.properties` (add `app.security.jwt.*` defaults)

**Depends on:** none.

**Reuses:** existing `@Configuration` pattern (`ApplicationBeanConfig`,
`KafkaMessagingConfig`); existing properties pattern (`app.messaging.kafka.*`,
`app.kafka.retry.*`).

**Requirements covered:** preparation for AUTH-03 (HS256 issuance + decoding wiring),
AUTH-24 (tests still pass).

**Done when:**

- [ ] `./mvnw verify` green; 88 existing tests still pass.
- [ ] Spring Boot context loads with `SecurityConfig` active.
- [ ] `SecurityFilterChain` permits everything (no test breaks).
- [ ] No `app.security.jwt.secret` leak into logs (visual check).

**Tests:** none new in this slice; gate = existing 88 tests stay green.
**Gate:** `./mvnw verify`.

---

### T2: Login endpoint — `POST /api/auth/login` + JwtIssuer + InMemoryUserStore + error handling

**What:** Implement the issuance side end-to-end. New `AuthController` under
`adapter/security/login/` exposes `POST /api/auth/login`. `JwtIssuer` validates
credentials via `BCryptPasswordEncoder.matches`, builds `JwtClaimsSet`
(`iss`/`sub`/`scope`/`iat`/`exp`), and encodes through `NimbusJwtEncoder`.
`InMemoryUserStore` holds the two demo users. Add
`InvalidCredentialsException` and extend `ApiExceptionHandler` to return
`{codigo:INVALID_CREDENTIALS, mensagem}` 401 and
`{codigo:INVALID_LOGIN_PAYLOAD, mensagem}` 400.

**Where:**

- `src/main/java/.../adapter/security/login/AuthController.java` (new)
- `src/main/java/.../adapter/security/login/LoginRequest.java` (new — record)
- `src/main/java/.../adapter/security/login/LoginResponse.java` (new — record)
- `src/main/java/.../adapter/security/login/JwtIssuer.java` (new)
- `src/main/java/.../adapter/security/login/InMemoryUserStore.java` (new)
- `src/main/java/.../adapter/security/login/DemoUser.java` (new — record)
- `src/main/java/.../adapter/security/login/InvalidCredentialsException.java` (new)
- `src/main/java/.../adapter/web/ApiExceptionHandler.java` (extend)

**Depends on:** T1.

**Reuses:** the existing `{codigo, mensagem}` envelope (`ApiExceptionHandler` +
`RejectionCode` enum precedent); the `@RestController` + `@PostMapping` shape of
`InvoiceController`.

**Requirements covered:** AUTH-01 .. AUTH-07, AUTH-18 (users seeded).

**Done when:**

- [ ] `curl -X POST localhost:8080/api/auth/login -d '{"username":"demo","password":"demo123"}'`
      returns 200 with a valid HS256 JWT decoded by `jwt.io` against the configured
      secret.
- [ ] Wrong password returns 401 `{codigo:INVALID_CREDENTIALS, ...}`.
- [ ] Missing `username` or `password` returns 400 `{codigo:INVALID_LOGIN_PAYLOAD, ...}`.
- [ ] BCrypt verification used (no plaintext compare).
- [ ] `./mvnw verify` green.

**Tests:** integration tests come in T5 (deferred so the implementation diff is
clean of test churn). Manual smoke check via `curl` covers this slice's done-when.
**Gate:** `./mvnw verify`.

---

### T3: Lock down invoice endpoints — `oauth2ResourceServer().jwt()` + scope enforcement + custom 401/403

**What:** Flip `SecurityFilterChain` from permissive to enforcing. Add
`oauth2ResourceServer(o -> o.jwt(...))` so incoming `Authorization: Bearer ...` is
validated by the `JwtDecoder` bean from T1. Require scope `invoice:write` on
`POST /api/orders/generate-invoice` and the legacy alias. Keep `/api/auth/login`
and the actuator endpoints permitted. Wire custom
`ApiBearerAuthenticationEntryPoint` (401) and `ApiBearerAccessDeniedHandler` (403)
so auth errors carry the `{codigo, mensagem}` envelope. **This slice will break the
existing integration tests temporarily** — T4 fixes them in the very next commit.

**Where:**

- `src/main/java/.../adapter/security/SecurityConfig.java` (edit filter chain)
- `src/main/java/.../adapter/security/error/ApiBearerAuthenticationEntryPoint.java` (new)
- `src/main/java/.../adapter/security/error/ApiBearerAccessDeniedHandler.java` (new)

**Depends on:** T1, T2.

**Reuses:** the existing `{codigo, mensagem}` envelope (`RejectionCode` /
`ErrorResponse` DTO pattern).

**Requirements covered:** AUTH-08 .. AUTH-17, AUTH-19, AUTH-20.

**Done when:**

- [ ] `curl -X POST localhost:8080/api/orders/generate-invoice -d @...` without
      `Authorization` header returns 401 with `{codigo:UNAUTHORIZED, ...}` envelope.
- [ ] Same with a valid `Bearer` token returns 200 + the unchanged invoice JSON.
- [ ] Actuator endpoints still 200 with no token.
- [ ] `./mvnw test` shows the expected ~5-10 existing integration tests failing on
      401 (these get fixed in T4 — commit T3 + T4 back-to-back, **don't push T3 alone**).

**Tests:** the expected failures are the proof T3 worked. T4 makes them green again.
**Gate:** intermediate (`./mvnw compile` green; `./mvnw test` failures must be 401s
on the protected endpoints, not other errors).

---

### T4: Update existing tests — `JwtTestSupport` + attach token in 4 integration tests

**What:** Add `JwtTestSupport` to `src/test/java/.../testsupport/`, exposing
`mintToken(String username, String... scopes)` that uses Nimbus + the test-profile
secret. Attach `Authorization: Bearer <token>` in the 4 integration tests that hit
`/api/orders/generate-invoice` or the legacy alias. Add
`application-test.properties` with the test secret if it doesn't already exist;
otherwise extend the existing one.

**Where:**

- `src/test/java/.../testsupport/JwtTestSupport.java` (new)
- `src/test/resources/application-test.properties` (new or extend)
- `src/test/java/.../web/InvoiceControllerIntegrationTest.java` (attach token)
- `src/test/java/.../adapter/web/MetricsIntegrationTest.java` (attach token)
- `src/test/java/.../adapter/integration/InvoiceKafkaFlowIntegrationTest.java` (attach token)
- `src/test/java/.../tracing/HttpTracePropagationIntegrationTest.java` (attach token)

**Depends on:** T3 (the breakage T4 fixes was created by T3).

**Reuses:** `NoOpKafkaTestConfig` pattern (shared test-support config).

**Requirements covered:** AUTH-21, AUTH-22, AUTH-23, AUTH-24.

**Done when:**

- [ ] `./mvnw verify` green; 88 existing tests pass.
- [ ] `ActuatorPrometheusIntegrationTest` untouched.
- [ ] `InvoiceGeneratorApplicationTests` untouched.
- [ ] No usage of `@WithMockUser` or `addFilters=false` (real token only).

**Tests:** existing 88 pass.
**Gate:** `./mvnw verify`.

---

### T5: Auth integration tests — login flow + filter chain coverage

**What:** Two new test classes. `AuthControllerIntegrationTest` covers login success
(200 + valid JWT), wrong password (401 `INVALID_CREDENTIALS`), missing fields (400
`INVALID_LOGIN_PAYLOAD`). `SecurityIntegrationTest` covers the protected
`/api/orders/generate-invoice` endpoint against {no token (401), malformed (401),
expired (401), valid-but-no-scope (403), valid-with-scope (200)}.

**Where:**

- `src/test/java/.../adapter/security/AuthControllerIntegrationTest.java` (new)
- `src/test/java/.../adapter/security/SecurityIntegrationTest.java` (new)

**Depends on:** T4 (uses `JwtTestSupport` for the malformed/expired/scope variants).

**Reuses:** the `@SpringBootTest` + `MockMvc` pattern from `InvoiceControllerIntegrationTest`;
`NoOpKafkaTestConfig` to skip the Kafka context wiring.

**Requirements covered:** validates AUTH-01..AUTH-12 end-to-end.

**Done when:**

- [ ] Both new tests pass. Total fast test count = 88 + new ≥ 95.
- [ ] Every assertion uses real HTTP status + JSON-path assertion on
      `codigo` / `mensagem` (no `expected exception is x` shortcuts).
- [ ] `./mvnw verify` green.

**Tests:** the new test classes themselves are the deliverable.
**Gate:** `./mvnw verify`.

---

### T6: Docs + Postman + ROADMAP + STATE

**What:** Wrap up. Update `docs/auth-strategy.md` ("What this codebase actually
ships" section flipping the demo from documented-only to implemented). Add an
"Authentication" section to `docs/business-rules.md` showing the login flow + the
`Authorization: Bearer` header on the invoice endpoints. Update `README.md` with a
two-step curl example. Flip the "Autenticação / autorização" row in
`README-CHALLENGE.md` from 🟡 to ✅ + add a F-AUTH bullet under M4. Update the
Postman collection (F-POSTMAN): add a `01 - Auth — Login` request, add a
collection-level Pre-request script that auto-logs-in and stores `accessToken` on a
collection variable, update every existing protected request to use
`Authorization: Bearer {{accessToken}}`. Add F-AUTH to `ROADMAP.md` under new M4
milestone, status COMPLETE. Add AD-032 to `STATE.md` recording the four design
decisions (HS256, in-memory users, scope authZ, JwtTestSupport).

**Where:**

- `docs/auth-strategy.md` (extend)
- `docs/business-rules.md` (new section)
- `README.md` (curl example + "Where to look" pointer)
- `README-CHALLENGE.md` (table row + M4 bullet)
- `docs/postman/invoice-generator.postman_collection.json` (new login request +
  Pre-request script + Authorization header on protected requests)
- `.specs/project/ROADMAP.md` (new M4 + F-AUTH entry)
- `.specs/project/STATE.md` (header update + AD-032)
- `CLAUDE.md` (update curl example to show login first)

**Depends on:** T1..T5 (everything implemented).

**Reuses:** the F-DEPLOY-ACTION / F-AWS ROADMAP entry shape; the AD-NNN numbering
convention in STATE.md.

**Requirements covered:** AUTH-25 .. AUTH-30, Success Criteria items 4..6.

**Done when:**

- [ ] `npx newman run docs/postman/invoice-generator.postman_collection.json` green
      end-to-end (Pre-request script auto-logs-in; all assertions pass).
- [ ] `grep -r "ADR-031" docs/ .specs/` returns no stale auth references (sanity).
- [ ] ROADMAP shows M4 with F-AUTH ✅.
- [ ] STATE shows AD-032 with all four design decisions recorded.
- [ ] CLAUDE.md curl example reflects the protected endpoint (or links to README's
      two-step flow).

**Tests:** none new (docs).
**Gate:** `npx newman run docs/postman/invoice-generator.postman_collection.json`.
