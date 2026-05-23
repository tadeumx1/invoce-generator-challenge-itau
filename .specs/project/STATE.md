# State

**Last Updated:** 2026-05-22
**Current Work:** Project initialization (no active feature work yet — first feature will be F-SAFETY-NET)

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

### AD-005: Terraform as default IaC for the AWS deployment (2026-05-22)

**Decision:** Use Terraform (not CDK) for the IaC artifact under F-AWS.
**Reason:** Industry-default for AWS provisioning; not coupled to the application language; reviewable in plain HCL by anyone (challenge reviewer needn't know Java CDK). User confirmed *documented + IaC* was desired but did not pin Terraform vs CDK; defaulting to Terraform with this ADR as a reversible decision point.
**Trade-off:** CDK lets us colocate infra with the Java app and reuse types; Terraform requires duplicating naming conventions in HCL.
**Impact:** F-AWS plans Terraform modules under `infra/terraform/`. If user prefers CDK before F-AWS starts, revisit this ADR.

---

## Active Blockers

### B-001: Lombok 1.18.22 incompatible with JDK 16+

**Discovered:** 2026-05-22
**Impact:** Builds fail on the active shell JDK (21); only succeed under JDK 11. Every `./mvnw` invocation needs `JAVA_HOME=/Users/matheustadeu/Library/Java/JavaVirtualMachines/temurin-11.0.20/Contents/Home` until F-UPGRADE lands.
**Workaround:** Override `JAVA_HOME` per-command. Documented in `.specs/codebase/TESTING.md`.
**Resolution:** F-UPGRADE removes this entirely (Spring Boot 3 ships a modern Lombok). No action needed before that feature.

---

## Lessons Learned

### L-001: The `paylods/` typo is load-bearing for the path-based `curl` examples in our docs

**Context:** Documenting the `curl` example for the sample request in CLAUDE.md and translation-changelog.md.
**Problem:** Catching the misspelling mid-rename and "fixing" it would have invalidated every doc reference and broken the manual-test path until they were all updated.
**Solution:** Left as-is, captured in `CONCERNS.md` C-7 as a deferred cosmetic fix. Sweep with F-UPGRADE.
**Prevents:** Mixing cosmetic cleanup into a focused-scope refactor.

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

> Commits are pending — none of the above is in git yet beyond the initial commit `0780ce3`. To be staged when the user asks.

---

## Deferred Ideas

Ideas captured during work that belong in future features or phases. Prevents scope creep while preserving good ideas.

- [ ] Add an `Idempotency-Key` header on `POST /api/orders/generate-invoice` so retries are safe — **Captured during:** brownfield mapping. Belongs in F-RESILIENCE.
- [ ] Webhook receiver for the delivery system's async confirmation — **Captured during:** integrations doc. Belongs in F-RESILIENCE.
- [ ] Rename `src/main/resources/paylods/` → `payloads/` — **Captured during:** rename work (C-7).
- [ ] JaCoCo coverage gate in CI with per-layer thresholds (≥80 % domain/use-case, ≥60 % adapter) — **Captured during:** TESTING.md. Belongs in F-UPGRADE.
- [ ] Add `@Validated` + Bean Validation annotations on the `Order` payload (e.g., `@Positive`, `@NotNull`) — **Captured during:** brownfield mapping. Belongs in F-CLEAN (validation is application-layer).
- [ ] Document an ADR comparing CDK vs Terraform if the user wants to revisit AD-005 — **Captured during:** AWS sizing question.

---

## Todos

In-progress thoughts and action items that don't fit in active tasks.

- [ ] Before F-DEFECTS-FUNCTIONAL starts, confirm with product (or document a defensible default) for: (a) JURIDICA + `taxRegime ∈ {OUTROS, null}` policy, (b) missing-delivery-region freight policy.
- [ ] Decide F-AWS compute target (ECS Fargate vs Lambda) before writing Terraform modules. Default lean: ECS Fargate (predictable behavior, no cold starts, fits the synchronous critical path of `RegistrationService`).
- [ ] Add `.gitignore` entries for `.specs/` if the user wants the spec-driven artifacts kept local (currently included; recommend keeping them committed for review).

---

## Preferences

**Model Guidance Shown:** never
