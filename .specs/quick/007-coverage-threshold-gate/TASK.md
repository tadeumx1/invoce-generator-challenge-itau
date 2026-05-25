# Quick Task 007 — JaCoCo coverage gate active (85 % line / 75 % branch)

> Numbering: quick task `005` is `aws-diagram-icons`; quick task `006` introduced the
> ReportGenerator-based HTML coverage view (richer than the stock JaCoCo HTML).
> This task `007` is the natural follow-up: now that the report is readable, the
> gate can fail builds on threshold violations.

**Feature:** F-SAFETY-NET / F-UPGRADE follow-up.
**Scope:** Spec-driven docs sync — reflect the now-enforced JaCoCo coverage gate
shipped in commit `70ce7ee` (2026-05-25).

## Why

`./mvnw verify` now fails below **85 % line** and **75 % branch** coverage at the
bundle level, with a curated exclude list (bootstrap, Spring configuration,
DTOs / requests / responses, exceptions, use-case interfaces, `domain/port/**`,
`domain/model/**`, integration-event envelopes, Kafka topic/header constants,
`RejectionCode`, demo-user data carriers).

Several spec-driven docs still described the gate as "report-only / deferred",
or as a per-layer plan (≥80 % domain/use-case, ≥60 % adapter) — the actual
implementation diverged to bundle-level thresholds with strategic excludes.
This task syncs the docs to the shipped reality.

## What ships

- `.specs/codebase/TESTING.md` — Coverage Targets section reflects the enforced
  thresholds, the exclude policy, and points readers at the layers the gate
  protects.
- `.specs/codebase/STACK.md` — drop the "report-only; no threshold enforced"
  qualifier.
- `.specs/codebase/CONCERNS.md` — close C-9's residual-risk note.
- `.specs/project/STATE.md` — tick the deferred-items checkbox; add **AD-036**
  recording the threshold + exclude-policy choice and the divergence from the
  originally-deferred per-layer target.
- `.specs/project/ROADMAP.md` — remove the coverage-gate Future Considerations
  line.
- `.specs/features/upgrade/spec.md` — annotate the out-of-scope row so future
  readers see where the gate eventually landed.
- `.specs/features/safety-net/spec.md` — same annotation on its out-of-scope
  row.

No source code, build configuration, or tests change in this quick task —
those changes already shipped in `70ce7ee`.

## Done when

- `grep -nR "report-only\|no threshold enforced\|coverage gate in CI\|Coverage gate in CI" .specs/`
  returns no live references (only historical/closed mentions remain).
- AD-036 is recorded in `STATE.md` with the threshold values, the exclude
  list, and the divergence rationale from the originally-deferred per-layer plan.
- The deferred-ideas checkbox for "JaCoCo coverage gate in CI" is checked.
- ROADMAP Future Considerations no longer lists the coverage gate.
- F-UPGRADE and F-SAFETY-NET out-of-scope rows annotate where the gate landed
  (this quick task / commit `70ce7ee`).
