# State

**Last Updated:** 2026-05-23
**Current Work:** F-SAFETY-NET, F-UPGRADE, F-CLEAN, and F-DEFECTS-FUNCTIONAL complete. Next: F-DEFECTS-PERFORMANCE.

---

## Recent Decisions (Last 60 days)

### AD-001: English-language rename of all Java identifiers (2026-05-22)

**Decision:** Translate Java packages, classes, methods, fields, comments to English. Keep JSON keys snake_case Portuguese and enum *values* Portuguese.
**Reason:** User preference for English code; cleaner long-term; aligns the codebase with how the rest of the team will likely read it. README's no-payload-change rule keeps the contract intact via `@JsonProperty`.
**Trade-off:** Mixed-language enums (`PersonType.FISICA` looks odd at first glance). Documented in `docs/business-rules.md` §7 glossary.
**Impact:** Every downstream feature can refer to English names. Translation audit captured in `docs/translation-changelog.md`.

### AD-002: Preserve the legacy behavior bit-for-bit during the rename (2026-05-22)

**Decision:** No business logic changes were made during the Portuguese→English rename, including the known defects (static list, OUTROS fallthrough, freight=0, double money).
**Reason:** Rename and bug fix are different categories of change; mixing them poisons the diff.
**Trade-off:** Tests still flaky after the rename (the static-list bug is preserved exactly as before).
**Impact:** Defects are addressed under M2 (F-DEFECTS-*), not earlier.

### AD-003: Sequencing — safety net → upgrade → Clean Arch → defects → ops (2026-05-22)

**Decision:** Build real tests first, then upgrade Java/Spring, then refactor to Clean Architecture, then fix defects within the new structure, then ops.
**Reason:** Refactoring without tests is dangerous; upgrading after refactoring forces double work. User confirmed this ordering.
**Trade-off:** Visible improvements (defect closure, observability) come later. The challenge reviewer may not see the "wow" features until M3.
**Impact:** M1 is internal-quality work; output is invisible to API consumers.

### AD-004: All 10 README themes in scope for v1 (2026-05-22)

**Decision:** Treat every theme from the README's "O que considerar na solução" as an in-scope feature, grouped into M1/M2/M3 milestones.
**Reason:** The challenge brief is the contract; partial coverage looks like a partial solution.
**Trade-off:** Larger scope than a "minimum delivery" approach.
**Impact:** ROADMAP.md has 8 features across 3 milestones.

### AD-006: Use Maven profile for slow-tag execution (2026-05-22; updated 2026-05-23)

**Decision:** Surefire 2.22.2 (inherited from the old Spring Boot 2.6.2 baseline) could not reliably filter by JUnit 5 `@Tag` through CLI overrides. Expose slow tests via a `<profile id="slow">` rather than CLI properties. After F-UPGRADE, the explicit Surefire version pin was removed and the Spring Boot 3.5.14 parent manages Surefire 3.5.5.
**Reason:** Spent ~6 attempts trying `-Dgroups=slow` + `-DexcludedGroups=` combinations against surefire 2.22.2; none worked. 3.2.5 + profile is the simplest stable path. Empty `<excludedGroups></excludedGroups>` in the profile silently fails to override the base — using a sentinel value like `<excludedGroups>none</excludedGroups>` is required.
**Trade-off:** None currently; plugin management is back under the Spring Boot parent.
**Impact:** Slow tests run via `./mvnw test -Pslow`. Documented in `CLAUDE.md` and the F-SAFETY-NET tasks.

### AD-007: Spring Boot 3.5.14 + Java 21 for F-UPGRADE (2026-05-23)

**Decision:** Upgrade directly from Spring Boot 2.6.2 / Java 11 to Spring Boot 3.5.14 / Java 21.
**Reason:** 3.5.14 is the newest Spring Boot 3.x parent available in Maven Central at implementation time; the challenge asks for Boot 3.x, not Boot 4.x.
**Trade-off:** This takes the latest 3.x line rather than the newer 4.x line, preserving the feature scope and avoiding an unnecessary major-version jump.
**Impact:** `./mvnw verify` runs on the default JDK 21 shell without the old `JAVA_HOME` workaround. Lombok is now managed at a JDK-21-compatible version.

### AD-008: Spotless + Checkstyle as the F-UPGRADE style gate (2026-05-23)

**Decision:** Add Spotless with google-java-format and a Checkstyle import policy. Bind both to `verify`.
**Reason:** Formatting should be mechanical, and the first enforceable rule should be low-noise: no wildcard, redundant, or unused imports.
**Trade-off:** The initial Spotless application touched most Java files. This is format-only churn, isolated inside F-UPGRADE.
**Impact:** `./mvnw verify` is now the CI-style command: tests, formatting check, Checkstyle, and JaCoCo report.

### AD-009: Clean Architecture layering with adapter-owned JSON DTOs (2026-05-23)

**Decision:** Split the code into `domain`, `application`, and `adapter` packages. Keep domain/application free of Spring and Jackson by moving JSON `@JsonProperty` annotations to DTOs under `adapter/web/dto`.
**Reason:** The next defect-fix phase needs stable use-case and port boundaries before changing behavior.
**Trade-off:** The web adapter now maps between DTOs and domain models explicitly, adding boilerplate.
**Impact:** HTTP payloads stay unchanged while business logic becomes testable without Spring or transport concerns.

### AD-010: Durable async over fire-and-forget threads (2026-05-23)

**Decision:** Do not treat detached threads or untracked `CompletableFuture.runAsync` as the production solution for outbound side effects. Current code stays synchronous until F-DEFECTS-PERFORMANCE/F-RESILIENCE introduces Kafka-backed durable async processing.
**Reason:** Fire-and-forget can hide errors, lose work on process shutdown, provide no retry, and weaken traceability.
**Trade-off:** Request latency remains high until Kafka producer/consumer dispatch is implemented.
**Impact:** ROADMAP points performance/resilience work toward Kafka producers/consumers with retry/DLQ, with `CompletableFuture` only acceptable as a documented local experiment.

### AD-013: Kafka for simulated external service calls (2026-05-23)

**Decision:** Every integration adapter/client call currently represented by `Thread.sleep(...)` is treated as a simulated asynchronous external service call. The four side-effect calls after invoice generation must be dispatched through Kafka: stock deduction, invoice registration, delivery scheduling, and accounts receivable.
**Reason:** These downstream services may be unavailable or slow. Kafka gives a durable async boundary so the HTTP request can return while consumers retry until the service is available or route exhausted failures to DLQ.
**Trade-off:** The invoice response can no longer mean all downstream side effects have completed; completion becomes eventually consistent and must be observable.
**Impact:** F-DEFECTS-PERFORMANCE now has a dedicated Kafka spec/tasks folder. F-RESILIENCE and F-AWS should use Kafka/MSK retry/DLQ language instead of generic queue/SQS wording unless the user revisits this decision.

### AD-014: Kafka chosen, SQS noted as simpler AWS alternative (2026-05-23)

**Decision:** Keep Kafka as the chosen implementation for F-DEFECTS-PERFORMANCE because the user explicitly wants Kafka and a local Docker Compose stack with Kafka.
**Reason:** The roadmap is also a technical demonstration; Kafka makes topics, partitions, consumer groups, retry topics, DLT, and consumer idempotency visible.
**Trade-off:** For this exact production workload, SQS would likely be simpler in AWS. The four integrations are command-style side effects with one logical consumer each, so they mostly need decoupling, retry, and DLQ rather than Kafka stream replay, long-lived event logs, or many subscriber groups.
**Impact:** Specs must implement Kafka now, but F-AWS can mention SQS as a lower-operational-complexity alternative if the architecture proposal compares options.

### AD-015: Local consumers in repo, production consumers in downstream services (2026-05-23)

**Decision:** For the technical test, keep Kafka publisher and consumers in this same Spring Boot codebase. For ideal production architecture, invoice-generator publishes only; stock, fiscal registration, delivery, and accounts-receivable services own their consumers.
**Reason:** The single-repo implementation makes the Kafka flow demonstrable with Docker Compose. The production boundary keeps downstream business behavior with the teams/services that own those domains.
**Trade-off:** The local implementation is intentionally less decomposed than the production architecture.
**Impact:** Specs and docs must call same-codebase consumers a local/demo compromise, not the target production ownership model.

### AD-016: HTTP invoice response means generated plus dispatched (2026-05-23)

**Decision:** `POST /api/orders/generate-invoice` returns the generated invoice after domain calculation and successful Kafka publication of downstream integration events.
**Reason:** The user expects the endpoint to generate the invoice. Making downstream systems async should not make invoice calculation itself async.
**Trade-off:** HTTP success does not mean stock deduction, fiscal registration, delivery scheduling, or accounts-receivable posting has completed. Those become eventually consistent operations.
**Impact:** Current JSON response shape remains unchanged for the challenge, but future API evolution should consider explicit processing status or a status endpoint.

### AD-011: Invalid fiscal/freight inputs reject with typed HTTP 400 (2026-05-23)

**Decision:** For F-DEFECTS-FUNCTIONAL, reject unsupported or missing juridica tax regimes and missing/null delivery region with `InvalidInvoiceOrderException`, mapped by the web adapter to HTTP 400 and JSON fields `codigo` and `mensagem`.
**Reason:** The previous behavior hid data quality problems by returning `items=[]` or `freightValue=0`, which matched the README's reported value inconsistencies.
**Trade-off:** Some malformed requests that previously returned 200 now fail fast. This is intentional because those 200 responses were wrong invoices.
**Impact:** C-2 and C-3 are resolved. Future validation can add Bean Validation, but the domain policy is already explicit.

### AD-012: BigDecimal money with HALF_EVEN scale 2 (2026-05-23)

**Decision:** Use `BigDecimal` for domain and DTO monetary fields. Calculated money is rounded with scale 2 and `RoundingMode.HALF_EVEN`.
**Reason:** Primitive `double` arithmetic is unsuitable for invoice money and was tracked as C-4.
**Trade-off:** Values like `72 × 1.048` now return `75.46` instead of `75.456`. The API still serializes numbers, but calculated values are now BRL-scale.
**Impact:** C-4 is resolved. Tests compare `BigDecimal` values semantically.

### AD-005: Terraform as default IaC for the AWS deployment (2026-05-22)

**Decision:** Use Terraform (not CDK) for the IaC artifact under F-AWS.
**Reason:** Industry-default for AWS provisioning; not coupled to the application language; reviewable in plain HCL by anyone (challenge reviewer needn't know Java CDK). User confirmed *documented + IaC* was desired but did not pin Terraform vs CDK; defaulting to Terraform with this ADR as a reversible decision point.
**Trade-off:** CDK lets us colocate infra with the Java app and reuse types; Terraform requires duplicating naming conventions in HCL.
**Impact:** F-AWS plans Terraform modules under `infra/terraform/`. If user prefers CDK before F-AWS starts, revisit this ADR.

---

## Active Blockers

None.

## Resolved Blockers

### B-001: Lombok 1.18.22 incompatible with JDK 16+ — resolved 2026-05-23

**Discovered:** 2026-05-22
**Impact before fix:** Builds failed on the active shell JDK (21) and only succeeded under JDK 11 with a `JAVA_HOME` override.
**Resolution:** F-UPGRADE moved the project to Java 21 + Spring Boot 3.5.14. `./mvnw verify` passes on the default JDK 21 shell.

---

## Lessons Learned

### L-001: The `paylods/` typo is load-bearing for the path-based `curl` examples in our docs

**Context:** Documenting the `curl` example for the sample request in CLAUDE.md and translation-changelog.md.
**Problem:** Catching the misspelling mid-rename and "fixing" it would have invalidated every doc reference and broken the manual-test path until they were all updated.
**Solution:** Left as-is, captured in `CONCERNS.md` C-7 as a deferred cosmetic fix. Sweep with F-UPGRADE.
**Prevents:** Mixing cosmetic cleanup into a focused-scope refactor.

### L-003: Stream.findFirst() rejects null first-element — C-3 has TWO broken paths, not one (2026-05-22)

**Context:** Writing characterization tests for SAFETY-19 (delivery address with `region=null`) during F-SAFETY-NET execution.
**Problem:** Spec said the buggy path produces `freight=0` (same as SAFETY-18). Actual behavior: `Stream<Region>::findFirst()` throws `NullPointerException` on a null element — the request fails with HTTP 500, not a silent zero.
**Solution:** Updated `MissingRegionFreightCharacterizationTest` to `assertThrows(NullPointerException.class, ...)`, marked SPEC_DEVIATION in code, updated `business-rules.md` §6.3 and `CONCERNS.md` C-3 to document both broken paths separately.
**Update 2026-05-23:** F-CLEAN T11 removed the accidental NPE path by mapping `Address::getRegion` after finding the matching address. F-DEFECTS-FUNCTIONAL then closed the policy gap by rejecting missing/null delivery region with HTTP 400 and code `INVALID_DELIVERY_REGION`.
**Prevents:** Assuming code "silently returns wrong value" without actually running it. When characterizing a defect, run the code first.

### L-002: A rename that "looks behavior-preserving" can still surface latent test ordering bugs

**Context:** After the English rename, `./mvnw test` failed on a test that *had* passed in isolation.
**Problem:** Initially looked like the rename broke something; it actually exposed the pre-existing static-list bug (C-1) — both legacy and renamed code accumulate in the same way; the test was always order-dependent.
**Solution:** Verified by running the test in isolation (passes) and re-reading the original `CalculadoraAliquotaProduto` (identical static pattern). Captured as the defining example of C-1.
**Prevents:** Mistaking pre-existing bugs for rename regressions; always re-verify the pre-rename behavior before claiming regression.

---

## Quick Tasks Completed

| #   | Description                                            | Date       | Commit  | Status  |
| --- | ------------------------------------------------------ | ---------- | ------- | ------- |
| 001 | Initial CLAUDE.md (init skill)                         | 2026-05-22 | (HEAD)  | ✅ Done |
| 002 | English rename of all Java identifiers                 | 2026-05-22 | (HEAD)  | ✅ Done |
| 003 | docs/business-rules.md (frozen contract)               | 2026-05-22 | (HEAD)  | ✅ Done |
| 004 | docs/translation-changelog.md (rename audit)           | 2026-05-22 | (HEAD)  | ✅ Done |
| 005 | Brownfield mapping (7 docs)                            | 2026-05-22 | (HEAD)  | ✅ Done |
| 006 | PROJECT.md + ROADMAP.md + STATE.md initialized         | 2026-05-22 | (HEAD)  | ✅ Done |
| 007 | F-SAFETY-NET spec.md + tasks.md                        | 2026-05-22 | (HEAD)  | ✅ Done |
| 008 | F-SAFETY-NET Phase 1 — calculator → bean, ctor inject, JaCoCo + surefire profile, test builders | 2026-05-22 | (HEAD) | ✅ Done |
| 009 | F-SAFETY-NET Phase 2 — 9 service test classes (brackets, freight, 4 characterizations)            | 2026-05-22 | (HEAD) | ✅ Done |
| 010 | F-SAFETY-NET Phase 3 — MockMvc integration + JaCoCo report verified                                | 2026-05-22 | (HEAD) | ✅ Done |
| 011 | F-UPGRADE — Java 21, Spring Boot 3.5.14, Spotless, Checkstyle, docs/spec updates                    | 2026-05-23 | (HEAD) | ✅ Done |
| 012 | F-CLEAN — Clean Architecture layers, ports/adapters, DTO mapping, docs/spec updates                 | 2026-05-23 | (HEAD) | ✅ Done |
| 013 | F-CLEAN follow-up — switch-based freight calculation, null-safe region lookup, async guidance       | 2026-05-23 | (HEAD) | ✅ Done |
| 014 | F-DEFECTS-FUNCTIONAL — resolve C-1 through C-4 with stateless tax, 400 validation, BigDecimal money | 2026-05-23 | (HEAD) | ✅ Done |

> Commits are pending — none of the above is in git yet beyond the initial commit `0780ce3`. To be staged when the user asks.

---

## Deferred Ideas

Ideas captured during work that belong in future features or phases. Prevents scope creep while preserving good ideas.

- [ ] Add an `Idempotency-Key` header on `POST /api/orders/generate-invoice` so retries are safe — **Captured during:** brownfield mapping. Belongs in F-RESILIENCE.
- [ ] Webhook receiver for the delivery system's async confirmation — **Captured during:** integrations doc. Belongs in F-RESILIENCE.
- [ ] Rename `src/main/resources/paylods/` → `payloads/` — **Captured during:** rename work (C-7).
- [ ] JaCoCo coverage gate in CI with per-layer thresholds (≥80 % domain/use-case, ≥60 % adapter) — **Captured during:** TESTING.md. Belongs in F-UPGRADE.
- [ ] Add `@Validated` + Bean Validation annotations on the `Order` payload (e.g., `@Positive`, `@NotNull`) — **Captured during:** brownfield mapping. Belongs in a future validation hardening task; F-DEFECTS-FUNCTIONAL already covers C-2/C-3 domain policy.
- [ ] Document an ADR comparing CDK vs Terraform if the user wants to revisit AD-005 — **Captured during:** AWS sizing question.

---

## Todos

In-progress thoughts and action items that don't fit in active tasks.

- [x] F-DEFECTS-FUNCTIONAL policy chosen and implemented: reject `JURIDICA + OUTROS/null` and missing/null delivery region with typed HTTP 400 responses.
- [ ] Decide F-AWS compute target (ECS Fargate vs Lambda) before writing Terraform modules. Default lean: ECS Fargate (predictable behavior, no cold starts, fits the synchronous critical path of `RegistrationService`).
- [ ] Add `.gitignore` entries for `.specs/` if the user wants the spec-driven artifacts kept local (currently included; recommend keeping them committed for review).
- [ ] During F-DEFECTS-PERFORMANCE, implement Kafka async dispatch for `stockPort`, `invoiceRegistrationPort`, `deliveryPort`, and `accountsReceivablePort` and add retry/DLQ/idempotency coverage.

---

## Preferences

**Model Guidance Shown:** never
