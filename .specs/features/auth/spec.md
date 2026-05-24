# F-AUTH — JWT Authentication with Login Endpoint Specification

**Status:** Draft (2026-05-23)
**Milestone:** M4 — Security & access control (new)
**Scope decision (user, 2026-05-23):** **Demo-grade in-app JWT** issued and validated by
the invoice-generator itself, deliberately diverging from the edge-validates pattern
recommended in [`docs/auth-strategy.md`](../../../docs/auth-strategy.md) and
ADR-032 of `docs/aws-architecture.md`. The user heard the recommendation and chose this
implementation as a *demonstration of the OAuth2 resource server pattern in Spring
Boot 3.5.x*, not as the production architecture.

## Problem Statement

The challenge brief lists "Autenticação / autorização" as one of the items expected in
the architectural proposal. The previous F-AWS feature documented two paths (Cognito
vs external IdP) and deferred implementation; `docs/auth-strategy.md` explained when
each path makes sense.

The user wants an executable demonstration that exercises the OAuth2 Resource Server
side of Spring Security in this same Spring Boot codebase, so that:

1. A reviewer can hit `POST /api/auth/login` with username + password, get a JWT, and
   replay it against `POST /api/orders/generate-invoice`.
2. Calls to the invoice endpoints without a valid token return HTTP 401; calls without
   the required scope return HTTP 403.
3. The implementation is honestly demo-grade — in-memory user store, HS256 symmetric
   key, no refresh tokens, no key rotation — and the docs say so loudly. The
   production path stays as documented in `docs/auth-strategy.md`.

This is an **additive** feature: no existing behaviour changes for clients that present
a valid token. Existing tests that hit the protected endpoints must be updated to mint
and attach a token, otherwise they break the build.

## Scope Decision Matrix

| Choice                    | Selected                                                | Rationale                                                                                                                                                                                                                                              |
| ------------------------- | ------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Auth model                | **In-app issuer + resource server**                     | User explicitly chose this over the edge-validates pattern. The trade-off is documented in `auth-strategy.md`; this spec implements the chosen path.                                                                                                  |
| Token signing             | **HS256 symmetric (HMAC) with shared secret**           | Simpler than RS256+JWKS for a single-app demo: no keypair management, no JWKS endpoint, one property to configure. RS256 + JWKS is the documented production upgrade path (see AD-032).                                                              |
| User store                | **In-memory, 2 hardcoded users with BCrypt hashes**     | Mirrors the demo-grade `IdempotencyStore` precedent (AD-024 already established the "in-memory for demo, durable in prod" pattern). Hardcoded users are loaded by `InMemoryUserStore`; passwords stored as BCrypt hashes (no plain text on disk).      |
| Token format              | **OAuth2-shaped response** (`access_token`, `token_type`, `expires_in`, `scope`) | Matches what client libraries expect from an OAuth2 token endpoint; future integration with a real IdP is a drop-in replacement.                                                                                                                       |
| Token expiry              | **60 minutes**                                          | Standard access-token lifetime. No refresh token (out of scope — re-login is fine for a demo).                                                                                                                                                         |
| Authorization model       | **Scope-based** (`invoice:write`, `invoice:admin`)      | Demonstrates the `SCOPE_*` authority pattern Spring Security maps from JWT `scope` claims. `demo` user gets `invoice:write`; `admin` gets `invoice:write invoice:admin`.                                                                                |
| Endpoints protected       | `POST /api/orders/generate-invoice` + legacy alias       | These are the only mutating endpoints in the app. Actuator health/info/prometheus stay public (Prometheus scraping does not authenticate). `/api/auth/login` is public by definition.                                                                  |
| Spring layer placement    | **`adapter/security/`**                                 | Clean Architecture: `domain` and `application` stay free of Spring Security imports. New package mirrors `adapter/web`, `adapter/messaging`, `adapter/observability`.                                                                                  |
| Test strategy             | **`JwtTestSupport` test utility minting real HS256 tokens** | Tests exercise the full filter chain — closer to production behaviour than `@WithMockUser` or `@AutoConfigureMockMvc(addFilters=false)`. The shared test secret lives in `application-test.properties`.                                              |

## Goals

- [ ] **AUTH-G1:** `POST /api/auth/login {"username":"demo","password":"demo123"}`
      returns HTTP 200 with `{access_token, token_type:"Bearer", expires_in:3600, scope:"invoice:write"}`.
- [ ] **AUTH-G2:** `POST /api/orders/generate-invoice` (and legacy alias) returns 401 without
      a token, 401 with an invalid/expired token, 403 with a valid token lacking
      `invoice:write` scope, and 200 with a valid token and the scope.
- [ ] **AUTH-G3:** Public endpoints (`/actuator/health`, `/actuator/prometheus`,
      `/actuator/info`, `/api/auth/login`) stay open with no token.
- [ ] **AUTH-G4:** All existing 88 fast tests continue to pass (via `JwtTestSupport`
      attaching a token to integration tests that hit protected endpoints).
- [ ] **AUTH-G5:** `docs/auth-strategy.md` is updated to mark "in-app JWT" as
      implemented for demo purposes, with an explicit warning that this is **not** the
      production recommendation.
- [ ] **AUTH-G6:** `ROADMAP.md` flips F-AUTH to COMPLETE under a new M4 milestone.

## Out of Scope

| Item                                                       | Reason                                                                                                                                |
| ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Refresh tokens                                             | Demo. Re-login on expiry is fine.                                                                                                     |
| User registration / password change endpoints              | Demo. The 2 hardcoded users are enough to demonstrate auth flow.                                                                      |
| User management (CRUD)                                     | Demo. Real IdP owns this in production.                                                                                                |
| RS256 + JWKS endpoint                                      | Captured as the production upgrade path in `docs/auth-strategy.md`. HS256 keeps the demo to one property.                              |
| Multi-tenancy / `X-Tenant-Id` claim                        | The invoice contract is single-tenant. Multi-tenant support is a separate feature spec.                                                |
| Per-resource authorization (user X can only see invoice Y) | Authorization here is endpoint-level (scope `invoice:write`). Resource-level rules need a domain model that doesn't exist yet.         |
| Audit log of login attempts                                | F-OBSERVABILITY already logs every HTTP request as structured JSON; rate-limiting / lock-out is a hardening feature.                  |
| Rate limiting / brute-force protection                     | Belongs in a separate hardening feature. Bucket4j or Spring rate-limit would be the natural choice.                                    |
| Per-request scope mapping to method security               | We use `HttpSecurity.requestMatchers(...).hasAuthority("SCOPE_invoice:write")` for the demo — no `@PreAuthorize` annotations.          |
| Token revocation / blacklist                               | HS256 self-contained tokens are not revocable without a blacklist; out of scope. Production RS256 with short TTL + refresh covers this. |

---

## User Stories

### P1: Demo login endpoint ⭐ MVP

**User Story:** As a reviewer of the technical challenge, I want to POST a username
and password to `/api/auth/login` and receive a JWT, so that I can exercise the
authenticated invoice flow end-to-end.

**Why P1:** Without the login endpoint, the rest of the feature is unreachable.

**Acceptance Criteria:**

- [ ] **AUTH-01:** `POST /api/auth/login` accepts `{"username":"...","password":"..."}`
      JSON. Both fields are required.
- [ ] **AUTH-02:** Valid credentials return HTTP 200 with body
      `{"access_token":"eyJ...", "token_type":"Bearer", "expires_in":3600, "scope":"..."}`.
- [ ] **AUTH-03:** The JWT is HS256-signed with the secret from
      `app.security.jwt.secret`, contains `sub` (username), `scope` (space-separated),
      `iat`, `exp`, and `iss` (`invoice-generator`) claims.
- [ ] **AUTH-04:** Invalid credentials (unknown username OR wrong password) return
      HTTP 401 with body `{"codigo":"INVALID_CREDENTIALS", "mensagem":"..."}` — same
      error envelope shape the rejection endpoints already use (consistency with C-2 /
      C-3 / C-4 rejection contract).
- [ ] **AUTH-05:** Missing `username` or `password` field returns HTTP 400
      `{"codigo":"INVALID_LOGIN_PAYLOAD", "mensagem":"..."}`.
- [ ] **AUTH-06:** Login responses never include the password or the user's stored hash.
- [ ] **AUTH-07:** Password comparison uses BCrypt (`BCryptPasswordEncoder.matches`);
      stored hashes never appear in logs.

### P1: Protected invoice endpoint ⭐ MVP

**User Story:** As an API consumer, I want the invoice endpoints to require a valid
JWT, so that anonymous traffic is rejected and authorized traffic flows through with
no behaviour change.

**Acceptance Criteria:**

- [ ] **AUTH-08:** `POST /api/orders/generate-invoice` without an `Authorization`
      header returns HTTP 401.
- [ ] **AUTH-09:** Same endpoint with `Authorization: Bearer <malformed>` returns 401.
- [ ] **AUTH-10:** Same endpoint with a token whose `exp` is in the past returns 401.
- [ ] **AUTH-11:** Same endpoint with a valid token that does NOT carry
      `invoice:write` scope returns HTTP 403.
- [ ] **AUTH-12:** Same endpoint with a valid token carrying `invoice:write` returns
      HTTP 200 and the unchanged invoice response (no behavioural change vs the
      pre-F-AUTH contract).
- [ ] **AUTH-13:** The legacy alias `POST /api/pedido/gerarNotaFiscal` is protected
      with the same rules as the canonical endpoint.

### P1: Public endpoints stay public

**User Story:** As an SRE, I want actuator endpoints and `/api/auth/login` to remain
publicly reachable, so that Prometheus scraping, health checks, and the login flow
itself are not blocked by auth.

**Acceptance Criteria:**

- [ ] **AUTH-14:** `GET /actuator/health` returns 200 with no token (`UP`).
- [ ] **AUTH-15:** `GET /actuator/prometheus` returns 200 with no token, so the
      F-OBSERVABILITY scrape contract is preserved.
- [ ] **AUTH-16:** `GET /actuator/info` returns 200 with no token.
- [ ] **AUTH-17:** `POST /api/auth/login` itself is reachable with no token (else
      nobody could ever log in).

### P2: Scope-based authorization

**User Story:** As a future spec author, I want a working scope-based authorization
example, so that adding `invoice:admin`-only endpoints later is a one-line filter
chain change rather than a new auth model.

**Acceptance Criteria:**

- [ ] **AUTH-18:** Two demo users are seeded: `demo` (password `demo123`, scope
      `invoice:write`) and `admin` (password `admin123`, scope
      `invoice:write invoice:admin`).
- [ ] **AUTH-19:** Spring Security's `JwtAuthenticationConverter` maps the `scope`
      JWT claim to `SCOPE_<value>` authorities (the framework default).
- [ ] **AUTH-20:** `HttpSecurity` requires `hasAuthority("SCOPE_invoice:write")` on
      the invoice endpoints (canonical + legacy alias).

### P3: Existing tests stay green

**User Story:** As a contributor, I want every existing integration test to keep
running, so that adding auth doesn't introduce a regression on the 88-test safety
net we built in F-SAFETY-NET through F-OBSERVABILITY.

**Acceptance Criteria:**

- [ ] **AUTH-21:** A `JwtTestSupport` utility under `src/test/java/.../testsupport/`
      mints valid HS256 tokens using the test secret from `application-test.properties`.
- [ ] **AUTH-22:** Every integration test hitting `/api/orders/generate-invoice` or
      the legacy alias is updated to attach `Authorization: Bearer <token>` via
      `JwtTestSupport`.
- [ ] **AUTH-23:** Tests hitting only actuator endpoints (`ActuatorPrometheusIntegrationTest`)
      are not changed (actuator stays public).
- [ ] **AUTH-24:** `./mvnw verify` is green; total fast test count grows from 88 to
      88 + N (N new auth tests in T5), with no test deletions.

### P3: Honest documentation

**User Story:** As a future reviewer or operator, I want the docs to be explicit
that this is a demo-grade auth implementation and not the recommended production
path, so that nobody mistakes it for an architectural recommendation.

**Acceptance Criteria:**

- [ ] **AUTH-25:** `docs/auth-strategy.md` gets a new section "What this codebase
      actually ships" stating that F-AUTH is an in-app demo, contradicts the
      edge-validates recommendation, and the production path is still
      Cognito/external-IdP at the API Gateway.
- [ ] **AUTH-26:** `docs/business-rules.md` adds an "Authentication" section
      describing the login flow and the `Authorization: Bearer` requirement on the
      invoice endpoints.
- [ ] **AUTH-27:** `README.md` shows a 2-step curl example: login → use the returned
      token on the invoice endpoint.
- [ ] **AUTH-28:** `README-CHALLENGE.md` flips the "Autenticação / autorização" row
      from 🟡 to ✅ with a pointer to F-AUTH and a note that the demo intentionally
      diverges from the documented production recommendation.
- [ ] **AUTH-29:** The Postman collection (F-POSTMAN) gets a `01 - Auth — Login`
      request and a Pre-request script that auto-logs-in and stores the token on the
      collection variable `accessToken`, so every protected request just uses
      `Authorization: Bearer {{accessToken}}`.
- [ ] **AUTH-30:** A new ADR (AD-032 in `STATE.md`) records the four key design
      decisions: HS256 over RS256, in-memory user store, scope-based authZ,
      `JwtTestSupport` test pattern.

---

## Success Criteria

F-AUTH is COMPLETE when:

1. ✅ `./mvnw verify` is green.
2. ✅ The two-step curl flow in AUTH-27 works against a freshly booted
   `./mvnw spring-boot:run`.
3. ✅ `newman run docs/postman/invoice-generator.postman_collection.json` is green
   end-to-end (including the auto-login Pre-request script).
4. ✅ `ROADMAP.md` lists F-AUTH under M4 with status COMPLETE.
5. ✅ `STATE.md` records AD-032.
6. ✅ `docs/auth-strategy.md`, `docs/business-rules.md`, `README.md`,
   `README-CHALLENGE.md` all mention the new auth flow.

---

## Future Considerations

- Move to RS256 + JWKS endpoint (the production upgrade path documented in
  `docs/auth-strategy.md`).
- Replace `InMemoryUserStore` with a real user directory (database or LDAP).
- Add refresh tokens + `POST /api/auth/refresh`.
- Add rate limiting on `/api/auth/login` (Bucket4j or Spring rate-limit).
- Method security via `@PreAuthorize` once a use case justifies it.
- Per-resource authorization (e.g., user X can only see invoices for tenant Y) — needs
  a multi-tenant data model first.
- Audit log specifically for auth events (login success/failure, scope-denied),
  emitted on a dedicated `auth.events.v1` Kafka topic so security tooling can subscribe.
- Replace HS256 with RS256 + an external IdP at the API Gateway boundary, deprecating
  the in-app login endpoint per the edge-validates recommendation.
