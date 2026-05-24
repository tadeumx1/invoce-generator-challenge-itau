# Authentication & Authorization Strategy

**Status:** Documented, not implemented (per F-AWS scope; see
[`docs/aws-architecture.md` ADR-032](aws-architecture.md)).
**Audience:** SREs / staff engineers deciding when to flip the public endpoint
from open to authenticated, and what shape the auth layer should take.

## TL;DR

- **Validate JWTs at the edge (API Gateway), not at every microservice.**
  Cognito User Pool or an external IdP (Auth0 / Keycloak / Okta) issues the
  token; API Gateway's JWT authorizer verifies signature / `exp` / `iss` /
  `aud` before the request reaches the VPC.
- Internal services (this one) **trust the propagated claims** carried as
  headers from the gateway. They do not re-validate the JWT.
- The Spring `spring-boot-starter-oauth2-resource-server` is **only** added
  if the service is exposed *without* a trusted gateway in front of it
  (defense-in-depth case), or if a stakeholder explicitly requires a
  second validation layer.
- Today this codebase has **no auth code**. ADR-032 freezes the
  Cognito-vs-external-IdP comparison; the rest is deferred until a real
  use case lands.

---

## Why "edge validates, services trust" is the microservices default

The naive instinct is to drop `spring-boot-starter-oauth2-resource-server`
into every Spring Boot service so each one independently validates the
incoming JWT. That works, but it scales poorly and creates failure modes
that are hard to debug:

| Problem with per-service JWT validation | What it looks like in practice |
| --- | --- |
| Duplicated JWKS fetching | Every service polls the IdP's `/.well-known/jwks.json`. N services × M restarts/day = N×M extra calls. With Cognito's rate limits or Auth0's tier limits, this becomes a real cost. |
| Inconsistent validation | One service upgrades Nimbus JOSE while another stays behind; a token validates in one and fails in another. The bug manifests as "intermittent 401s" depending on which pod the request lands on. |
| Key rotation pain | When the IdP rotates signing keys, every service has to refresh its JWKS cache. If one lags, requests fail until it does. |
| Local dev gets painful | Every developer needs a working IdP locally (Keycloak in docker-compose, mocked JWKS, etc.) or every service needs an `auth.enabled=false` profile. |
| Audit confusion | Which service rejected the request? The gateway, the BFF, or the downstream microservice? Each one logs its own 401. |

The mature microservices pattern is:

```
┌────────────┐    JWT     ┌──────────────────┐  trusted     ┌──────────────────┐
│  Client    │ ─────────▶ │ API Gateway      │  headers     │ invoice-generator│
│ (Bearer)   │            │ (JWT authorizer) │ ───────────▶ │   (this app)     │
└────────────┘            │ Cognito / Auth0  │              │                  │
                          └──────────────────┘              └──────────────────┘
```

- Gateway validates **once**, at the public boundary.
- Gateway strips or signs the relevant claims into headers
  (`X-User-Id`, `X-Tenant-Id`, `X-Scopes`, …).
- Internal services treat those headers as trusted input — same way they
  trust DB query results, because the headers come from a system inside the
  trust boundary (private VPC, mTLS, or a service mesh enforcing the
  topology).

The header propagation is *not* the JWT itself. It's the **already-validated
claims**. The JWT stays at the gateway.

## When you'd add `spring-boot-starter-oauth2-resource-server` here

There is exactly one case for adding the resource-server starter to this
specific app:

- **The invoice-generator is reachable without going through the gateway.**
  e.g., another microservice calls the internal ALB directly without
  going through the API Gateway, and the trust boundary on that path is
  weak (no mTLS, no service mesh enforcing identity).

In that case, defense in depth applies: validate the JWT a second time at
the service. The cost (JWKS fetch, signature verification) is real but
acceptable as a safety net against a misconfigured gateway or a leaked
internal endpoint.

If the architecture instead keeps internal traffic on a private network
with the gateway as the only ingress, **do not** add the starter.

## Two paths for the issuer (deferred to ADR-032)

The Cognito-vs-external-IdP comparison is captured verbatim in
[ADR-032](aws-architecture.md). Summary:

- **Cognito User Pool** — wins on simplicity for a single-tenant internal
  service. One AWS service to operate; native API Gateway integration.
- **External IdP (Auth0 / Keycloak / Okta)** — wins on flexibility for
  multi-tenant or federated user models. Pricing and SSO options are
  richer. The API Gateway side is identical — only the `issuer-uri`
  changes.

The choice belongs to a product/stakeholder decision, not an
implementation one.

## Header contract (when auth lands)

When the gateway starts validating JWTs, it propagates claims to internal
services through these headers. Keep this list stable so consumers can
depend on it:

| Header | Source claim | Required? | Notes |
| --- | --- | --- | --- |
| `X-User-Id` | `sub` | yes | Stable user identifier from the IdP. |
| `X-Tenant-Id` | custom claim (`tenant_id`) | conditional | Required only if multi-tenant. |
| `X-Scopes` | `scope` (space-separated) | yes | The OAuth scopes granted to the token. |
| `X-Correlation-Id` | n/a | yes | **Already used** by F-OBSERVABILITY for MDC + tracing; orthogonal to auth but listed here for completeness. |

These belong to the `adapter/web` boundary and would be parsed by a
`HandlerInterceptor` or filter into a request-scoped `AuthenticatedUser`
bean injected into the controllers — **not** by re-validating the JWT.

## What the invoice-generator would do in code (if implemented)

Two layers, only one would land here:

1. **Gateway-trusted header path (default for this service):**
   - Add a `RequestContextFilter` reading `X-User-Id` / `X-Tenant-Id` /
     `X-Scopes` from the headers, populating a request-scoped
     `AuthenticatedUser`.
   - Inject `AuthenticatedUser` where needed (controller or use-case
     boundary) to authorize per-tenant operations.
   - No Spring Security dependency.

2. **Resource-server path (only if direct exposure is in scope):**
   ```yaml
   # application.yml
   spring:
     security:
       oauth2:
         resourceserver:
           jwt:
             issuer-uri: https://<cognito-or-idp>/...
   ```
   ```java
   @EnableWebSecurity
   class SecurityConfig {
     @Bean SecurityFilterChain api(HttpSecurity http) throws Exception {
       return http
         .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("SCOPE_invoice:write"))
         .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
         .csrf(c -> c.disable())
         .build();
     }
   }
   ```
   Then `Authentication`/`@AuthenticationPrincipal Jwt` is available in
   the controller and authorization is enforced before the use case runs.

The clean-architecture rules in [`CLAUDE.md`](../CLAUDE.md) still apply:
the security wiring lives under `adapter/web` (or a new
`adapter/security` slice) — `domain/` and `application/` stay free of
Spring Security imports. The use case sees an `AuthenticatedUser` value
object, not a `Jwt`.

## What is **not** in scope here

- Service-to-service auth between this app and downstream Kafka consumers
  — that's already covered by MSK SASL/IAM (see
  [`docs/aws-architecture.md`](aws-architecture.md) and AD-013 in
  [`STATE.md`](../.specs/project/STATE.md)).
- mTLS / service mesh — would belong to a future feature if zero-trust
  inside the VPC is required.
- Per-resource authorization (e.g., "user X can only invoice for tenant
  Y"). This needs the `X-Tenant-Id` claim and is enforced at the
  application layer; the spec for that work doesn't exist yet.
- Refresh-token / token-revocation handling — that's the IdP's problem,
  not this service's.

## Related references

- [`docs/aws-architecture.md`](aws-architecture.md) — ADR-032 freezes the
  Cognito vs external IdP comparison and explains why F-AWS shipped
  *documented* AuthN/AuthZ instead of *provisioned*.
- [`.specs/project/STATE.md`](../.specs/project/STATE.md) — full ADR log
  (note: STATE uses `AD-NNN` numbering for project-level decisions;
  `docs/aws-architecture.md` uses `ADR-NNN` for AWS-only ones — the two
  sequences are independent).
- [`README-CHALLENGE.md`](../README-CHALLENGE.md) — "Autenticação /
  autorização" row in the Proposta de Arquitetura table.
