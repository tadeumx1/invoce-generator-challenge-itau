# F-API-DOCS — Tasks

**Spec:** [spec.md](spec.md) — 19 requirement IDs (DOCS-01..DOCS-19).
**Task granularity:** 3 vertical-slice tasks.
**Gate:** `./mvnw verify` after each task.

---

## T1 — Add springdoc dependency + `OpenAPIConfig` + endpoint annotations

**What:** Pull in `springdoc-openapi-starter-webmvc-ui`. Create `OpenAPIConfig` declaring
the `bearer-jwt` security scheme. Annotate `AuthController.login(...)` with
`@SecurityRequirements({})` (opt out of the global bearer requirement) and
`InvoiceController.generateInvoice(...)` with `@SecurityRequirement(name = "bearer-jwt")`.
Add `@Operation(summary, description)` on all three productive endpoints.

**Where:**
- `pom.xml` — new dependency block.
- `src/main/java/.../adapter/web/OpenAPIConfig.java` — new `@Configuration` class.
- `src/main/java/.../adapter/security/login/AuthController.java` — annotations on
  `login(...)`.
- `src/main/java/.../adapter/web/InvoiceController.java` — annotations on
  `generateInvoice(...)`.

**Depends on:** —

**Reuses:**
- The `@Configuration` style used by `SecurityConfig` / `KafkaMessagingConfig` —
  `OpenAPIConfig` follows the same `@Bean` factory shape.
- The two `@RequestMapping`-style routing already declared on the two controllers; no
  routing changes.

**Done when:**
- DOCS-01 — `pom.xml` carries the dependency, version pinned (2.x line compatible with
  Spring Boot 3.5.14).
- DOCS-04 — `@Operation(summary=..., description=...)` on the three productive endpoints.
- DOCS-05 — `OpenAPIConfig` bean declares title, version, server URL, and `bearer-jwt`
  scheme.
- DOCS-06 — `AuthController.login(...)` overrides security to empty.
- DOCS-07 — `InvoiceController.generateInvoice(...)` declares `@SecurityRequirement`.

**Tests:**
- 103 existing fast tests must continue to pass. T1 by itself adds no new test —
  enforcement lands in T3.

**Gate:** `./mvnw verify`.

---

## T2 — Permit the docs surface through `SecurityConfig` and the rate-limit filter

**What:** Add six URL patterns to `SecurityConfig`'s `permitAll` list and the same set to
the F-RATELIMIT exempt list (`RateLimitConfig` or wherever the exempt path matcher lives).

**Where:**
- `src/main/java/.../adapter/security/SecurityConfig.java` — `permitAll` block.
- `src/main/java/.../adapter/security/ratelimit/RateLimitConfig.java` (or the class that
  holds the exempt path list — discover at execution time; do NOT guess).

**Depends on:** T1 (the dependency must be on the classpath so the paths exist).

**Reuses:**
- The existing `permitAll` block on `SecurityConfig` (already permits
  `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, `POST /api/auth/login`).
  Same pattern, six new entries.
- The existing actuator-exempt logic in the rate-limit filter (RLIM-12..RLIM-15). Adding
  the swagger paths to the same set is a one-line change.

**Done when:**
- DOCS-09 — six patterns permitted in `SecurityConfig`.
- DOCS-10 — same six patterns exempt from F-RATELIMIT.
- DOCS-11 — anonymous `GET /v3/api-docs` works (verified by curl during execution).
- DOCS-12 — 100 rapid `GET /swagger-ui.html` calls do not 429 (verified manually + by T3
  test).

**Tests:** none net-new; behaviour is asserted via the T3 test.

**Gate:** `./mvnw verify`.

---

## T3 — `OpenApiDocsIntegrationTest` + ROADMAP/STATE/README/CLAUDE wiring

**What:** Write an integration test that hits `/v3/api-docs` and `/swagger-ui.html`
anonymously, asserts the OpenAPI document declares `bearer-jwt`, and asserts the
three productive paths are present. Then close documentation: README + CLAUDE links,
STATE AD-034, ROADMAP M6 F-API-DOCS status COMPLETE.

**Where:**
- `src/test/java/.../adapter/web/OpenApiDocsIntegrationTest.java` (new).
- `README.md` — Swagger UI subsection in Quickstart.
- `CLAUDE.md` — "API documentation" subsection.
- `.specs/project/STATE.md` — AD-034.
- `.specs/project/ROADMAP.md` — flip F-API-DOCS to COMPLETE under M6.

**Depends on:** T1, T2.

**Reuses:**
- `SecurityIntegrationTest` (AD-032) as the template for the new integration test —
  same MockMvc + production filter chain posture; same `@SpringBootTest` config.
- The AD-032 / AD-026 entries as the template for AD-034 structure.
- The M6 section in ROADMAP added by F-BULKHEAD's T3 — F-API-DOCS's T3 only adds a row
  under it.

**Done when:**
- DOCS-13, DOCS-14, DOCS-15 — `OpenApiDocsIntegrationTest` runs green, ≥3 assertions.
- DOCS-16, DOCS-17 — README + CLAUDE link to Swagger UI.
- DOCS-18 — STATE.md records AD-034 with the four scope decisions.
- DOCS-19 — ROADMAP.md shows F-API-DOCS under M6 status COMPLETE (date 2026-05-24).
- All spec requirements flip from Pending → Verified.

**Tests:**
- New file: `OpenApiDocsIntegrationTest` with ≥3 test methods.
- Existing 103+ fast tests stay green.

**Gate:** `./mvnw verify`.

---

## Coverage

| Task | Requirement IDs covered |
| --- | --- |
| T1 | DOCS-01, DOCS-04, DOCS-05, DOCS-06, DOCS-07 (plus DOCS-02 / DOCS-03 / DOCS-08 made observable by springdoc auto-wiring once T1 lands) |
| T2 | DOCS-09, DOCS-10, DOCS-11, DOCS-12 |
| T3 | DOCS-13, DOCS-14, DOCS-15, DOCS-16, DOCS-17, DOCS-18, DOCS-19 |

**Total:** 19 / 19 mapped. 0 unmapped.

Note: DOCS-02, DOCS-03, DOCS-08 are not assigned to a specific task because they describe
**emergent behaviour** of springdoc auto-discovery — they become true the instant T1 lands
and are continuously asserted by DOCS-13 / DOCS-14 in T3.
