# Quick Task 006 Summary — ReportGenerator HTML coverage view

## Result

Shipped a pinned ReportGenerator wrapper that consumes JaCoCo's XML and writes
a richer HTML coverage site at `target/site/coverage/index.html`. This is the
human-readable companion to JaCoCo's bundled `target/site/jacoco/index.html`
(which remains the canonical machine-readable report for CI and the
`./mvnw verify` gate).

## Changes (commit `9ce2fb3`, 2026-05-25)

- **`.config/dotnet-tools.json` (new):** pins
  `dotnet-reportgenerator-globaltool` at `5.5.10`. Restored idempotently by
  `dotnet tool restore` in the wrapper script.
- **`scripts/coverage-html.sh` (new, executable):**
  - Checks for `dotnet` and prints the Homebrew install hint if missing.
  - Runs `./mvnw verify` to refresh the JaCoCo XML.
  - `dotnet tool restore` then `dotnet tool run reportgenerator` rendering
    `target/site/jacoco/jacoco.xml` → `target/site/coverage/` with
    `-reporttypes:Html;HtmlSummary` and a friendly `-title`.
- **`pom.xml`:** first JaCoCo `<excludes>` entry —
  `**/InvoiceGeneratorApplication*`. The bootstrap class has no testable
  behavior beyond `SpringApplication.run(...)`; excluding it keeps the bundle
  ratio honest. Quick task `007` later expanded this exclude set into the
  full policy.
- **`README.md` + `docs/testing.md`:** document
  `scripts/coverage-html.sh` and the richer
  `target/site/coverage/index.html` alongside the existing JaCoCo HTML
  reference. CLAUDE.md was updated to mirror the README.

## What ReportGenerator buys over stock JaCoCo HTML

- **Sortable tables** across packages and classes (line %, branch %, methods,
  uncovered lines) — fast triage of which classes drag the bundle down.
- **Risk colouring** (red / orange / yellow / green) per class, so low-coverage
  hotspots jump off the page instead of being buried inside a package tree.
- **Inline source view** with hit-count gutters that match JaCoCo's
  highlighting but with a cleaner layout.
- **Filterable assembly/group view** — useful when reviewing a PR that touches
  one adapter without scrolling past the rest.

## Why a separate tool, not a JaCoCo plugin

JaCoCo's bundled HTML is intentionally minimal and stable; richer HTML reports
are out of scope for the JaCoCo project. ReportGenerator is the de-facto
ecosystem tool for prettier coverage HTML and supports JaCoCo XML directly.
Pinning via `dotnet-tools.json` keeps the version pinned per repo with no
global state.

## Trade-off

- **Requires .NET SDK on the contributor's machine** to render the prettier
  HTML. The CI pipeline does not need it (CI runs against the JaCoCo XML +
  threshold gate from quick task `007`). The wrapper script prints a clear
  `brew install --cask dotnet-sdk` hint if `dotnet` is missing, so the
  failure mode is obvious.

## Sequence with quick task 007

Quick task `006` (this one) made the coverage report **readable** so
contributors could see *what* the gate would protect. Quick task
`007-coverage-threshold-gate` then enabled the threshold gate
(`jacoco:check` at bundle ≥ 85 % line / ≥ 75 % branch) and expanded the
exclude list beyond `InvoiceGeneratorApplication`. See AD-036 in
`.specs/project/STATE.md` for the threshold rationale.

## Backfill note

This SUMMARY.md was written retroactively on 2026-05-25 alongside quick task
`007`, to give the ReportGenerator work its own spec-driven traceability slot.
The actual implementation shipped in commit `9ce2fb3` on the same day.
