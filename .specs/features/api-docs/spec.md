# F-API-DOCS — OpenAPI 3 / Swagger UI documentation surface

**Status:** Draft (2026-05-24)
**Milestone:** M6 — Concurrency back-pressure + DX polish (new)
**Owner:** project owner
**Companion feature:** [F-BULKHEAD](../bulkhead/spec.md) (same milestone)

## Scope decision (user, 2026-05-24)

- **Library:** `org.springdoc:springdoc-openapi-starter-webmvc-ui` (Spring Boot 3 line,
  current major 2.x). Single starter brings:
  - OpenAPI 3 spec generation at `GET /v3/api-docs` (JSON) and `GET /v3/api-docs.yaml`.
  - Swagger UI at `GET /swagger-ui.html` (with a redirect alias from `GET /swagger-ui/index.html`).
  - Auto-discovery of `@RestController` beans, request/response shapes, and bean-validation
    annotations.
- **Scope:** **demo + reviewer experience.** The challenge ships an OpenAPI document and a
  reachable Swagger UI for the two productive endpoints (`POST /api/auth/login` +
  `POST /api/orders/generate-invoice` and its legacy alias). Not in scope: contract-first /
  spec-first design, code generation from spec, externalised API gateway documentation.
- **Auth integration:** the OpenAPI document declares a **single security scheme `bearer-jwt`
  (HTTP, `bearerFormat: JWT`)** so Swagger UI's "Authorize" button issues
  `Authorization: Bearer <token>`. `/api/auth/login` is annotated as **not requiring** the
  scheme (it issues tokens). `/api/orders/generate-invoice` (and the legacy alias) require
  the scheme.
- **Security filter chain:** Swagger UI + `/v3/api-docs/**` are added to the **`permitAll`
  list** in `SecurityConfig` so they are reachable without a token. Rationale: a docs surface
  behind auth is a non-feature; reviewers expect `curl http://localhost:8080/swagger-ui.html`
  to load.
- **Rate-limit interaction:** Swagger UI + `/v3/api-docs/**` are added to the **F-RATELIMIT
  exempt list** (same reasoning as `/actuator/health`: operational, not API). Without this
  exemption, a reviewer clicking around Swagger UI could trip the `default` rate limiter.
- **Customisation level:** **light.** Spec defaults plus an `OpenAPIConfig` bean that sets
  title, version, server URL, contact, and the `bearer-jwt` security scheme. Method-level
  `@Operation(summary, description)` + `@SecurityRequirement` annotations on the three
  endpoints. **No DTO description annotations** (`@Schema(description=...)`) — the JSON
  contract is frozen (`docs/business-rules.md`), so DTO field meaning lives there, not in
  scattered annotations.

## Problem statement

The challenge currently exposes three HTTP endpoints (`POST /api/auth/login`,
`POST /api/orders/generate-invoice`, `POST /api/pedido/gerarNotaFiscal`) and one auth contract
(JWT bearer with `invoice:write` scope). Reviewers and future contributors discover this
surface through three artifacts:

- `README.md` `curl` snippets,
- `docs/auth-strategy.md` prose,
- `docs/postman/invoice-generator.postman_collection.json` + Newman runs.

None of them is **machine-readable**, none is **interactive**, and none is **navigable from
the running app**. A reviewer cannot point a browser at `http://localhost:8080/` and explore
the API. A future SDK / client generator has nothing to consume.

Adding `springdoc-openapi-starter-webmvc-ui` is the smallest possible diff that gives:

1. A **machine-readable OpenAPI 3 document** at `/v3/api-docs` — consumable by Postman
   imports, OpenAPI Generator, AWS API Gateway HTTP API integrations, IDE plugins, etc.
2. A **reachable interactive Swagger UI** at `/swagger-ui.html` — reviewers click "Try it
   out" instead of crafting `curl`s with `jq` for the bearer token.
3. Documented **bearer JWT authentication flow** with an "Authorize" button that mirrors the
   real F-AUTH contract.

This is purely additive. No existing endpoint changes shape. No DTO changes. No business rule
changes.

## Scope decision matrix

| Choice | Selected | Rationale |
| --- | --- | --- |
| Library | **`springdoc-openapi-starter-webmvc-ui` (latest 2.x for Spring Boot 3.5)** | De-facto standard for Spring Boot 3 OpenAPI 3 generation. Springfox is EOL. Single starter ships both spec generator + Swagger UI. |
| OpenAPI version | **OpenAPI 3.0.x** (springdoc default) | The 3.1 line exists but tooling lag (Postman import, AWS API Gateway HTTP API) still favours 3.0 in 2026. |
| Scope of annotations | **Three endpoint annotations + one config bean** | Just enough to surface the auth flow + endpoint summaries. No DTO `@Schema(description=…)` — those duplicate `docs/business-rules.md` and rot. |
| Auth scheme | **`bearer-jwt` (HTTP / bearerFormat=JWT)** | Matches the real F-AUTH contract. The `securitySchemes` block lets Swagger UI's "Authorize" populate the `Authorization` header. |
| Swagger UI auth on login endpoint | **`security: []` override** (opts out of the global requirement) | `/api/auth/login` issues the token; requiring a token to call it is a chicken-and-egg loop. The override is the documented OpenAPI pattern. |
| Filter-chain treatment | **`permitAll` for `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`** | Docs behind auth is a non-feature. Same posture as `/actuator/health` etc. |
| Rate-limit treatment | **Exempted, same set of patterns** | Reviewer clicking through Swagger should not deplete the `default` bucket. Same reasoning as RLIM-OOS-1 for actuator. |
| Servers list | **`http://localhost:8080` only** | Demo posture; AWS gateway URL is not provisioned (F-AWS is proposal-grade). |
| Versioning of the OpenAPI doc | **`info.version` = `pom.xml` `${project.version}`** | The Maven Resources plugin already filters `application.properties`; reuse the same mechanism if needed, or hard-code `0.0.1-SNAPSHOT` for the demo. Tracked as DOCS-OOS-3 (acceptable to hard-code). |
| Contract-first / spec-first | **Out of scope** | Project owner chose code-first. Spec-first is a bigger workflow change. |
| Generating client SDKs | **Out of scope** | The generated `/v3/api-docs` JSON is the substrate; downstream consumers can run `openapi-generator-cli` against it independently. |

## Goals

- [ ] **DOCS-G1:** `GET http://localhost:8080/swagger-ui.html` returns the Swagger UI HTML
      shell with the three endpoints visible (`POST /api/auth/login`,
      `POST /api/orders/generate-invoice`, `POST /api/pedido/gerarNotaFiscal`).
- [ ] **DOCS-G2:** `GET http://localhost:8080/v3/api-docs` returns a valid OpenAPI 3.0 JSON
      with:
      - `info.title = "Invoice Generator API"` (or similar),
      - `components.securitySchemes.bearer-jwt` declared,
      - `security: [{ bearer-jwt: [] }]` at the document level,
      - `/api/auth/login` overrides with `security: []`,
      - `/api/orders/generate-invoice` and `/api/pedido/gerarNotaFiscal` inherit the document-level requirement.
- [ ] **DOCS-G3:** Swagger UI's "Authorize" button accepts a JWT and propagates
      `Authorization: Bearer <token>` to the invoice endpoints; a real round-trip from
      Swagger UI to `POST /api/orders/generate-invoice` succeeds with a valid token.
- [ ] **DOCS-G4:** `/v3/api-docs/**` and `/swagger-ui/**` are reachable **without
      authentication** (`SecurityConfig` permits them) and **without being rate-limited**
      (F-RATELIMIT filter exempts them).
- [ ] **DOCS-G5:** A focused integration test asserts `/v3/api-docs` is reachable
      anonymously and that the OpenAPI document declares the `bearer-jwt` scheme. This
      closes the AD-029 "registered ≠ exercised" pattern for the OpenAPI surface.
- [ ] **DOCS-G6:** `./mvnw verify` is green; total fast test count grows by ≥1.
- [ ] **DOCS-G7:** `README.md` and `CLAUDE.md` link to the local Swagger UI URL; `STATE.md`
      records `AD-034` capturing the scope decisions; `ROADMAP.md` shows F-API-DOCS under
      M6 status COMPLETE.

## Out of scope

| Item | Reason | Tracker |
| --- | --- | --- |
| `@Schema(description=...)` on DTO fields | The JSON contract is frozen in `docs/business-rules.md`; per-field docs there are the SSOT. Scattered DTO annotations rot. | DOCS-OOS-1 |
| Spec-first / contract-first workflow | Bigger workflow change. Project owner picked code-first. | DOCS-OOS-2 |
| Versioning `info.version` from `pom.xml` via resource filtering | `0.0.1-SNAPSHOT` is acceptable for the demo; adding `<filtering>true</filtering>` on resources for one field is over-engineering. | DOCS-OOS-3 |
| OpenAPI 3.1 over 3.0 | Tooling support for 3.1 lags in 2026 (Postman, AWS API Gateway HTTP API). 3.0 is the safe choice. | DOCS-OOS-4 |
| Generated client SDKs (openapi-generator-cli) | The OpenAPI JSON is the substrate; SDK generation is a downstream consumer task, not part of F-API-DOCS. | DOCS-OOS-5 |
| Multi-server document (local + AWS gateway URL) | F-AWS is proposal-grade; no live gateway URL. | DOCS-OOS-6 |
| Swagger UI customisation (theme, logo) | Demo posture. Defaults are fine. | DOCS-OOS-7 |
| OpenAPI tag grouping by domain | Three endpoints — grouping is overkill. The default flat list is more readable. | DOCS-OOS-8 |
| Documenting error envelope (`{codigo, mensagem}`) via `@ApiResponse` references | The envelope is documented in `docs/business-rules.md` and via Newman assertions; adding `@ApiResponse(responseCode="400", content=...)` annotations is a lot of code for repeat-yourself documentation. | DOCS-OOS-9 |

## User stories

### P1: Reviewer hits `/swagger-ui.html` and sees the API ⭐ MVP

**User Story:** As a code-challenge reviewer, I want to point my browser at
`http://localhost:8080/swagger-ui.html` and see the three productive endpoints, so that I
can explore the API without reading any documentation file.

**Why P1:** This is the single highest "reviewer experience" win of the whole feature. Every
other AC is in service of this one working.

**Acceptance criteria:**

- [ ] **DOCS-01:** `pom.xml` declares `org.springdoc:springdoc-openapi-starter-webmvc-ui`
      with a version compatible with Spring Boot 3.5.14 (a `2.x` line; pin explicitly to
      keep upgrades reviewable).
- [ ] **DOCS-02:** `GET http://localhost:8080/swagger-ui.html` returns HTTP 200 + HTML.
- [ ] **DOCS-03:** Swagger UI lists exactly **three** operations:
      `POST /api/auth/login`, `POST /api/orders/generate-invoice`,
      `POST /api/pedido/gerarNotaFiscal`. Actuator endpoints do **not** appear (springdoc
      auto-filters management endpoints by default; explicit filter via
      `springdoc.paths-to-match=/api/**` if needed).
- [ ] **DOCS-04:** The three operations carry a `summary` (one-line title) and a `description`
      (one-paragraph operational note) via `@Operation(summary=..., description=...)`.

### P1: JWT bearer scheme is documented and works from Swagger UI

**User Story:** As a reviewer, I want to click "Authorize" in Swagger UI, paste a JWT, and
have it stick across the `/api/orders/generate-invoice` operations, so that I can exercise
the protected endpoints without leaving the UI.

**Acceptance criteria:**

- [ ] **DOCS-05:** An `OpenAPIConfig` `@Configuration` class declares a `@Bean OpenAPI`
      that:
      - Sets `info(title, version, description, contact)`.
      - Adds a `Server` entry for `http://localhost:8080` (description: "Local development").
      - Adds a single `SecurityScheme` named `bearer-jwt` with
        `type=HTTP`, `scheme=bearer`, `bearerFormat=JWT`.
      - Adds a document-level `SecurityRequirement` referencing `bearer-jwt`.
- [ ] **DOCS-06:** `AuthController.login(...)` carries
      `@SecurityRequirements({})` (or `@Operation(security = {})`) — overrides the
      document-level requirement to be **open** for the login endpoint.
- [ ] **DOCS-07:** `InvoiceController.generateInvoice(...)` carries
      `@SecurityRequirement(name = "bearer-jwt")` — inherits + reinforces the document-level
      requirement; the same annotation covers both URI mappings on the method
      (`/api/orders/generate-invoice`, `/api/pedido/gerarNotaFiscal`).
- [ ] **DOCS-08:** From Swagger UI: clicking "Authorize" → entering `<JWT>` → executing
      `POST /api/orders/generate-invoice` with a valid payload returns 200 OK. The
      `Authorization: Bearer <JWT>` header is visible in the request preview.

### P1: Docs surface is reachable + not throttled

**User Story:** As an operator, I want `/v3/api-docs/**` and `/swagger-ui/**` reachable
without auth and not subject to F-RATELIMIT, so that clicking through Swagger doesn't lock
out a reviewer.

**Acceptance criteria:**

- [ ] **DOCS-09:** `SecurityConfig.securityFilterChain(...)` adds the following patterns to
      the `permitAll` list **above** the `.anyRequest().authenticated()` rule:
      - `/v3/api-docs`
      - `/v3/api-docs/**`
      - `/v3/api-docs.yaml`
      - `/swagger-ui`
      - `/swagger-ui/**`
      - `/swagger-ui.html`
- [ ] **DOCS-10:** F-RATELIMIT's `RateLimitConfig` (or whichever class holds the exempt
      path list) treats the same six patterns as **exempt**. The filter short-circuits
      before invoking the limiter.
- [ ] **DOCS-11:** `GET /v3/api-docs` returns HTTP 200 + `application/json` to an
      **anonymous** caller (no `Authorization` header).
- [ ] **DOCS-12:** 100 rapid `GET /swagger-ui.html` calls from the same IP within 60 seconds
      do **not** receive HTTP 429 from F-RATELIMIT. The exemption must be airtight.

### P2: Wired ≠ exercised — integration test

**User Story:** As a maintainer, I want a test that fails if a refactor accidentally hides
the OpenAPI document behind auth or removes the `bearer-jwt` scheme, so that AD-029 stays
closed for this surface too.

**Acceptance criteria:**

- [ ] **DOCS-13:** A new test (`OpenApiDocsIntegrationTest`) asserts:
      - `GET /v3/api-docs` returns 200 to an **anonymous** caller.
      - The response body is JSON containing `"openapi"` (version key) and
        `"components"."securitySchemes"."bearer-jwt"`.
      - The response body lists paths `/api/auth/login`,
        `/api/orders/generate-invoice`, `/api/pedido/gerarNotaFiscal`.
      - `GET /swagger-ui.html` returns 200 to an anonymous caller.
- [ ] **DOCS-14:** The test uses the production `SecurityConfig` (no `addFilters=false`),
      same posture as `SecurityIntegrationTest` from AD-032.
- [ ] **DOCS-15:** The cardinality guard `CardinalityGuardTest` remains green (no new
      forbidden meter tags). Springdoc does not register Micrometer meters by default; if
      a hidden meter appears, the guard catches it.

### P3: Documentation closure

**Acceptance criteria:**

- [ ] **DOCS-16:** `README.md` adds a "Swagger UI" subsection under "Quickstart" with the
      one-liner: `Open http://localhost:8080/swagger-ui.html`.
- [ ] **DOCS-17:** `CLAUDE.md` adds an "API documentation" subsection under the F-AUTH
      block describing:
      - Where Swagger UI lives (`/swagger-ui.html`).
      - Where the raw OpenAPI spec lives (`/v3/api-docs`).
      - The `bearer-jwt` scheme and how to authorise from the UI.
      - That the docs surface is exempt from both auth (`permitAll`) and rate limiting.
- [ ] **DOCS-18:** `STATE.md` records **AD-034** capturing the four scope decisions
      (springdoc over Springfox, demo-light annotation level, `permitAll` + rate-limit
      exempt, no DTO `@Schema(description)`).
- [ ] **DOCS-19:** `ROADMAP.md` lists F-API-DOCS under M6 with status COMPLETE on landing
      (same M6 introduced by F-BULKHEAD's tasks T3).

## Edge cases

- WHEN `/v3/api-docs` is requested with `Accept: application/yaml` THEN springdoc SHALL
  return the YAML representation at `/v3/api-docs.yaml`. Both forms are part of the OpenAPI
  spec contract; both SHALL be in the `permitAll` set.
- WHEN a reviewer's `Authorization: Bearer <expired-token>` is rejected by the JWT filter
  THEN the response SHALL be the existing F-AUTH 401 envelope. Springdoc does not alter the
  envelope; this is pre-existing behaviour preserved.
- WHEN the JWT scope is `invoice:read` (a hypothetical future scope) and the user tries
  `POST /api/orders/generate-invoice` from Swagger UI THEN F-AUTH SHALL return 403 with the
  existing `FORBIDDEN` envelope. Swagger UI displays it as a 403 response. No envelope
  customisation in F-API-DOCS.
- WHEN the application starts with `springdoc.swagger-ui.enabled=false` (a hypothetical
  future production override) THEN `/swagger-ui.html` SHALL return 404 and the
  `permitAll` entry SHALL still be safe (an unmapped path is just a 404). The
  `permitAll` list is **stable across springdoc enabled/disabled**.
- WHEN a future feature adds a new `@RestController` under `/api/**` THEN it SHALL be
  auto-discovered by springdoc. No spec change required. If the controller should be
  hidden, it can carry `@Hidden` from springdoc — out-of-scope to enforce today.
- WHEN the springdoc dependency conflicts with an existing dependency at startup THEN the
  app SHALL fail fast at boot — and the integration test DOCS-13 will catch it on every
  PR. No silent regression possible.

## Requirement traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| DOCS-01 | P1: Reviewer hits Swagger UI | Tasks | Pending |
| DOCS-02 | P1: Reviewer hits Swagger UI | Tasks | Pending |
| DOCS-03 | P1: Reviewer hits Swagger UI | Tasks | Pending |
| DOCS-04 | P1: Reviewer hits Swagger UI | Tasks | Pending |
| DOCS-05 | P1: JWT bearer scheme | Tasks | Pending |
| DOCS-06 | P1: JWT bearer scheme | Tasks | Pending |
| DOCS-07 | P1: JWT bearer scheme | Tasks | Pending |
| DOCS-08 | P1: JWT bearer scheme | Tasks | Pending |
| DOCS-09 | P1: Docs reachable + unthrottled | Tasks | Pending |
| DOCS-10 | P1: Docs reachable + unthrottled | Tasks | Pending |
| DOCS-11 | P1: Docs reachable + unthrottled | Tasks | Pending |
| DOCS-12 | P1: Docs reachable + unthrottled | Tasks | Pending |
| DOCS-13 | P2: Integration test | Tasks | Pending |
| DOCS-14 | P2: Integration test | Tasks | Pending |
| DOCS-15 | P2: Integration test | Tasks | Pending |
| DOCS-16 | P3: Documentation closure | Tasks | Pending |
| DOCS-17 | P3: Documentation closure | Tasks | Pending |
| DOCS-18 | P3: Documentation closure | Tasks | Pending |
| DOCS-19 | P3: Documentation closure | Tasks | Pending |

**ID format:** `DOCS-NN`.
**Status values:** Pending → In Tasks → Implementing → Verified.
**Coverage:** 19 total — mapped to 3 vertical-slice tasks in `tasks.md`.

## Success criteria

F-API-DOCS is COMPLETE when:

1. ✅ `./mvnw verify` is green; total fast test count grows by ≥1.
2. ✅ `curl -s http://localhost:8080/v3/api-docs | jq .openapi` returns `"3.0.x"`.
3. ✅ `curl -s http://localhost:8080/v3/api-docs | jq '.components.securitySchemes."bearer-jwt".scheme'` returns `"bearer"`.
4. ✅ `curl -s http://localhost:8080/v3/api-docs | jq '.paths | keys'` includes
   `/api/auth/login`, `/api/orders/generate-invoice`, `/api/pedido/gerarNotaFiscal`.
5. ✅ `curl -I http://localhost:8080/swagger-ui.html` returns `200 OK` (or `302` to the
   real UI path; both are acceptable springdoc behaviours).
6. ✅ `STATE.md` records AD-034; `ROADMAP.md` shows F-API-DOCS under M6 status COMPLETE.
7. ✅ `README.md` + `CLAUDE.md` link to Swagger UI.
