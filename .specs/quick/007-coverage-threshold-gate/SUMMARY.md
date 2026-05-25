# Quick Task 007 Summary — JaCoCo coverage gate active

## Result

Spec-driven docs synced to the now-enforced JaCoCo coverage gate. `./mvnw verify`
fails the build below **85 % line** or **75 % branch** coverage at the
**bundle** level, with a curated exclude list that drops bootstrap, Spring
wiring, contracts, and data carriers from the measurement. The gate itself
shipped in commit `70ce7ee`; this task aligned the spec docs.

> **Quick-task numbering:** 005 = `aws-diagram-icons` (commit `f36a9a2`),
> 006 = `reportgenerator-coverage-view` (commit `9ce2fb3`, retroactively recorded
> alongside this task for traceability), 007 = this task (commit `70ce7ee` +
> the doc sweep below).

## Doc changes (this task)

| File | Change |
| --- | --- |
| `.specs/codebase/STACK.md` | Coverage tool line now lists the enforced thresholds + exclude policy instead of "report-only; no threshold enforced". |
| `.specs/codebase/TESTING.md` | "Coverage Targets" rewritten — enforced thresholds, full exclude list, the layers where the gate has teeth, and the divergence from the original per-layer plan. `verify` lines also reflect the active `jacoco:check`. |
| `.specs/codebase/CONCERNS.md` | C-9 residual-risk line marks the gate as active. |
| `.specs/project/STATE.md` | Deferred-ideas checkbox for the coverage gate is ticked (with the divergence note). New **AD-036** records the threshold values (85 % / 75 %), the exclude policy, the bundle-rule trade-offs, and why the implementation diverged from the deferred per-layer sketch. |
| `.specs/project/ROADMAP.md` | Coverage-gate Future Considerations line struck through and cross-linked to this quick task. |
| `.specs/features/upgrade/spec.md` | Out-of-scope row annotated with the closure (commit `70ce7ee`, AD-036). |
| `.specs/features/safety-net/spec.md` | Same annotation on its out-of-scope row. |

## Threshold values shipped

| Counter | Bundle minimum |
| --- | ---: |
| LINE | 85 % |
| BRANCH | 75 % |

## Excludes (`pom.xml` → `jacoco-maven-plugin > configuration > excludes`)

- `**/InvoiceGeneratorApplication*` — bootstrap.
- `**/*Config*`, `**/*Properties*` — Spring wiring / configuration properties.
- `**/*Dto*`, `**/*Request*`, `**/*Response*`, `**/*Exception*` — adapter contracts.
- `**/*UseCase*` — use-case interfaces (no executable code).
- `**/domain/port/**`, `**/domain/model/**` — domain contracts and data carriers.
- `**/adapter/messaging/IntegrationEvent*`,
  `**/adapter/messaging/InvoiceTopics*`,
  `**/adapter/observability/InvoiceKafkaHeaders*`,
  `**/adapter/observability/RejectionCode*` — static envelopes / constants.
- `**/adapter/security/login/DemoUser*` — demo-user data carriers.

## Divergence from the originally-deferred plan

`TESTING.md` and the F-UPGRADE / F-SAFETY-NET specs originally sketched a
per-layer enforcement (≥ 80 % domain/use-case, ≥ 60 % adapter). The shipped
gate uses a **single bundle rule** instead, with the exclude list above doing
the layer-shaping work explicitly. AD-036 in `STATE.md` records the rationale:
JaCoCo per-layer rules need careful per-package wiring (a wrong glob silently
exempts a layer); one bundle rule is simpler to reason about and tune.

## Verification

- Sequencing makes sense end-to-end: read JaCoCo HTML
  (`target/site/jacoco/index.html`) or the richer ReportGenerator HTML from
  quick task `006` (`target/site/coverage/index.html`), then `./mvnw verify`
  enforces.
- Spec-driven docs no longer describe the gate as deferred / report-only;
  the deferred-ideas checkbox in `STATE.md` is ticked; ROADMAP Future
  Considerations no longer lists it.
