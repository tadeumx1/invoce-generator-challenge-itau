# F-AUTH — Design

**Spec:** [`spec.md`](spec.md)
**Status:** Frozen (2026-05-23)

## Knowledge verification chain (per skill rules)

- **Codebase:** confirmed `pom.xml` has no Spring Security entry; `adapter/`
  packages today are `config`, `integration`, `messaging`, `observability`, `web`;
  `ApiExceptionHandler` already maps `InvalidInvoiceOrderException` → HTTP 400 with
  `{codigo, mensagem}` envelope (the auth errors will reuse this envelope shape).
- **Project docs:** `docs/auth-strategy.md` documents the edge-validates-services-trust
  pattern; ADR-032 in `docs/aws-architecture.md` captures the Cognito-vs-IdP comparison.
  Both stay authoritative; this design adds the chosen demo path on top.
- **Library reference:** Spring Boot 3.5.x ships Spring Security 6 (auto-config via
  `spring-boot-starter-security`); OAuth2 Resource Server lives in
  `spring-boot-starter-oauth2-resource-server`; HS256 issuance/validation done with
  Nimbus JOSE (`NimbusJwtEncoder` + `NimbusJwtDecoder`) — both classes are part of
  Spring Security 6 itself, no extra dep required.

## Architectural decisions

### AD-AUTH-1: HS256 over RS256 for the demo

**Decision:** Sign tokens with HS256 (HMAC-SHA-256) using a shared secret read from
`app.security.jwt.secret`. The same secret is used by the encoder (`POST /auth/login`)
and the decoder (request filter chain).

**Reason:** Single-app demo. RS256 + JWKS is the right answer when multiple services
verify the same issuer's tokens (each fetches public keys from `/.well-known/jwks.json`).
Here we have one service that issues and validates — symmetric is simpler, no keypair
storage, no JWKS endpoint, one property to configure. The trade-off is documented in
`docs/auth-strategy.md` and called out in `STATE.md` AD-032.

**Trade-off:** Any service that wants to verify these tokens needs the shared secret —
which doesn't scale. The production upgrade path is RS256 + JWKS at the IdP boundary.

### AD-AUTH-2: In-memory user store, BCrypt hashes

**Decision:** Hardcode two users in an `InMemoryUserStore` component, with
BCrypt-hashed passwords stored as constants (NOT plaintext). Mirrors the
`IdempotencyStore` pattern (AD-024) — in-memory for demo, durable in production.

**Reason:** Demo only. A real user directory (DB / LDAP / IdP) is a separate concern.
BCrypt hashes (instead of plaintext) so the demo at least demonstrates the correct
password storage primitive, even though the storage backend is wrong for production.

**Trade-off:** Process restart "loses" nothing (users are hardcoded), but every
production-grade concern (registration, password reset, lockout, audit) is missing.

### AD-AUTH-3: Scope-based authorization via `SCOPE_*` authorities

**Decision:** Issue tokens with a space-separated `scope` claim (`invoice:write`,
`invoice:write invoice:admin`). Spring Security's default `JwtAuthenticationConverter`
maps each scope to a `SCOPE_<value>` `GrantedAuthority`. The filter chain calls
`.hasAuthority("SCOPE_invoice:write")` on the invoice endpoints.

**Reason:** Standard OAuth2 pattern. Adding a future `invoice:admin`-only endpoint is
a one-line filter chain change. Method security (`@PreAuthorize`) is intentionally
deferred — no use case justifies it today.

**Trade-off:** Role-based (`hasRole("INVOICE_USER")`) would be equally valid and
shorter; scopes are slightly more verbose but align with OAuth2 idioms and the
documented JWT shape in `docs/auth-strategy.md`.

### AD-AUTH-4: `JwtTestSupport` minting real HS256 tokens in tests

**Decision:** Integration tests that hit protected endpoints attach a real
`Authorization: Bearer <token>` header. The token is minted in-process by
`JwtTestSupport` using the same Nimbus encoder + the test-profile secret. No mocks,
no `@WithMockUser`, no `addFilters=false`.

**Reason:** Exercises the full filter chain (decoder → authentication → scope check
→ controller). `@WithMockUser` skips the filter chain; `addFilters=false` skips
Spring Security entirely. Both hide real auth bugs. Minting tokens is two lines per
test and reuses production code paths.

**Trade-off:** Tests carry a tiny crypto dependency. Negligible — the secret is a
known test constant; encoding/decoding is microseconds.

### AD-AUTH-5: Reuse the existing `{codigo, mensagem}` error envelope

**Decision:** Auth errors return the same JSON shape as the existing rejection codes
(C-2 / C-3 / C-4): `{"codigo":"INVALID_CREDENTIALS","mensagem":"..."}` for 401,
`{"codigo":"INVALID_LOGIN_PAYLOAD","mensagem":"..."}` for 400. Spring Security's
default `BearerTokenAuthenticationEntryPoint` and `BearerTokenAccessDeniedHandler`
are replaced with handlers that emit this envelope.

**Reason:** Contract consistency. Clients already parse `{codigo, mensagem}` from
the invoice endpoints; auth errors should not invent a new shape.

**Trade-off:** Slightly more wiring (two custom handler beans). Worth it for one
shared error envelope across the whole API.

## Component breakdown

All new code under `src/main/java/br/com/itau/invoicegenerator/adapter/security/`
unless otherwise noted.

```
adapter/security/
├── SecurityConfig.java                  // @Configuration: SecretKey + JwtEncoder + JwtDecoder
│                                        // + PasswordEncoder + SecurityFilterChain beans
├── ApiSecurityProperties.java           // @ConfigurationProperties("app.security.jwt")
│                                        // fields: secret, issuer, expiry (Duration)
├── login/
│   ├── AuthController.java              // POST /api/auth/login
│   ├── LoginRequest.java                // record(String username, String password)
│   ├── LoginResponse.java               // record(String accessToken, String tokenType,
│   │                                    //        long expiresIn, String scope)
│   ├── JwtIssuer.java                   // verifies credentials + builds claims + encodes JWT
│   ├── InMemoryUserStore.java           // 2 hardcoded users with BCrypt hashes
│   ├── DemoUser.java                    // record(String username, String passwordHash, String scopes)
│   └── InvalidCredentialsException.java // RuntimeException
└── error/
    ├── ApiBearerAuthenticationEntryPoint.java   // 401 with {codigo, mensagem} envelope
    └── ApiBearerAccessDeniedHandler.java        // 403 with {codigo, mensagem} envelope

adapter/web/
└── ApiExceptionHandler.java             // EXTENDED: handle InvalidCredentialsException → 401,
                                         // MethodArgumentNotValid / HttpMessageNotReadable for
                                         // the login payload → 400 with INVALID_LOGIN_PAYLOAD.

src/main/resources/
├── application.properties               // app.security.jwt.* (with placeholder secret)
└── application-aws.properties           // override secret to ${SECURITY_JWT_SECRET}

src/test/java/.../testsupport/
├── JwtTestSupport.java                  // mintToken(username, scopes...) → HS256 String
└── application-test.properties          // app.security.jwt.secret=<test-only constant>

src/test/java/.../adapter/security/
├── AuthControllerIntegrationTest.java   // login success + INVALID_CREDENTIALS + INVALID_LOGIN_PAYLOAD
└── SecurityIntegrationTest.java         // protected endpoint × {no token, malformed, expired,
                                         //   valid no scope, valid with scope}
```

**Existing integration tests touched (T4):**

- `InvoiceControllerIntegrationTest` — 3 happy/contract tests; attach token in
  `@BeforeEach`.
- `MetricsIntegrationTest` — POSTs to the invoice endpoint to drive counters; attach
  token.
- `InvoiceKafkaFlowIntegrationTest` — POSTs to the invoice endpoint and asserts on
  Kafka; attach token.
- `HttpTracePropagationIntegrationTest` — POSTs to the invoice endpoint; attach token.
- `InvoiceGeneratorApplicationTests` — context load only; no change needed.
- `ActuatorPrometheusIntegrationTest` — actuator only; no change needed.

## Filter chain layout

```
HttpSecurity
  .csrf(disable)
  .sessionManagement(SessionCreationPolicy.STATELESS)
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
  .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwtDecoder)));
```

## JWT shape

```json
// Header (HS256)
{ "alg": "HS256", "typ": "JWT" }

// Payload
{
  "iss": "invoice-generator",
  "sub": "demo",
  "scope": "invoice:write",
  "iat": 1717000000,
  "exp": 1717003600
}
```

## Properties

```properties
# application.properties — demo defaults (override in -aws profile)
app.security.jwt.secret=change-me-please-this-is-a-demo-only-32-char-secret!!
app.security.jwt.issuer=invoice-generator
app.security.jwt.expiry=PT1H
```

The default secret is 64 ASCII characters (HS256 requires ≥256 bits = 32 bytes). The
test-profile secret is a different constant in `application-test.properties` so
test-minted tokens are not accidentally usable in prod-like environments.

## Test strategy by layer

| Test layer | Pattern | Files |
| --- | --- | --- |
| Unit (JWT issuance) | Plain JUnit, no Spring context | `JwtIssuerTest` (covered indirectly by integration tests; new dedicated unit test only if logic grows) |
| Integration (auth endpoint) | `@SpringBootTest` + `MockMvc` | `AuthControllerIntegrationTest` |
| Integration (filter chain) | `@SpringBootTest` + `MockMvc` + `JwtTestSupport` | `SecurityIntegrationTest`, existing tests in T4 |
| Manual smoke | `curl` flow in README | n/a |
| Newman / Postman | F-POSTMAN collection with auto-login Pre-request | `docs/postman/invoice-generator.postman_collection.json` |

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Adding Spring Security accidentally breaks the F-OBSERVABILITY metrics integration test, the F-DEFECTS-PERFORMANCE Kafka flow test, or the F-OBSERVABILITY trace propagation test | Each protected-endpoint test is updated in T4 with `JwtTestSupport`. The actuator-only `ActuatorPrometheusIntegrationTest` is untouched so the Prometheus scrape contract stays intact. |
| Newman fails because the Postman collection's existing requests don't carry the token | T6 adds a collection-level Pre-request script that auto-calls `/auth/login` if `{{accessToken}}` is unset; all protected requests use `Authorization: Bearer {{accessToken}}`. |
| The `BearerTokenAuthenticationEntryPoint` default returns plain 401 with no body, breaking the `{codigo, mensagem}` contract | T1 wires custom `ApiBearerAuthenticationEntryPoint` and `ApiBearerAccessDeniedHandler` that emit the existing envelope. |
| HS256 secret leaks into JaCoCo report or test logs | Secret is a property with no `@Value` access in production code outside the `SecurityConfig` bean wiring; tests use a known constant; no `log.info(secret)` anywhere. Reviewed in T5. |
| Existing curl in CLAUDE.md / README breaks for users who copy-paste it | README and CLAUDE.md show the new two-step flow (login → use token); the single-line curl moves to a "no-auth quick mode" sidenote in CLAUDE.md (or is deleted). |
