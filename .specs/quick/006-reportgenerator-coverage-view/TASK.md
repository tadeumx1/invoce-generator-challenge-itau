# Quick Task 006 — ReportGenerator HTML coverage view

**Feature:** F-SAFETY-NET / observability follow-up.
**Scope:** Add a richer human-readable HTML view of the JaCoCo coverage data,
on top of the stock JaCoCo HTML report.

## Why

JaCoCo's bundled HTML report (`target/site/jacoco/index.html`) is the canonical
machine-readable artifact, but it is sparse for human inspection: no sortable
columns, no risk-coloured class lists, no metric trend over time, no hotspot
summaries. Reviewing PRs against the JaCoCo HTML means clicking through one
package at a time.

ReportGenerator (the .NET tool that consumes JaCoCo XML and renders a richer
HTML site — sortable tables, class-level risk colouring, history support) gives
contributors a much better view of which classes are dragging the bundle down.
We already have the JaCoCo XML at `target/site/jacoco/jacoco.xml`; the only
piece missing is a reliable, pinned tool invocation.

This task was a prerequisite for quick task `007-coverage-threshold-gate`:
contributors need to be able to read the coverage report before we start
failing builds on it.

## What ships

- `.config/dotnet-tools.json` — pins
  `dotnet-reportgenerator-globaltool` at `5.5.10` so the tool version is
  reproducible across machines and CI (no `dotnet tool install -g` drift).
- `scripts/coverage-html.sh` — one-shot wrapper:
  1. Checks `dotnet` is installed (prints `brew install --cask dotnet-sdk` if
     not).
  2. Runs `./mvnw verify` to refresh `target/site/jacoco/jacoco.xml`.
  3. `dotnet tool restore` (idempotent — fetches the pinned ReportGenerator on
     first run).
  4. `dotnet tool run reportgenerator` rendering JaCoCo XML →
     `target/site/coverage/index.html` with `-reporttypes:Html;HtmlSummary` and
     `-title:invoicegenerator coverage`.
- `pom.xml` — first JaCoCo `<excludes>` entry added
  (`**/InvoiceGeneratorApplication*`) so the bootstrap class does not pull the
  bundle ratio down. Later expanded by quick task `007`.
- `README.md` + `docs/testing.md` — point readers at
  `scripts/coverage-html.sh` and the richer `target/site/coverage/index.html`.

## Done when

- `scripts/coverage-html.sh` is executable and renders
  `target/site/coverage/index.html` from a clean checkout.
- `dotnet tool restore` resolves ReportGenerator 5.5.10 deterministically from
  `.config/dotnet-tools.json`.
- README + `docs/testing.md` document the workflow.
- Quick task `007` can cite `target/site/coverage/index.html` as the
  human-readable companion to `target/site/jacoco/index.html`.

## Out of scope

- Enforcing coverage thresholds — quick task `007-coverage-threshold-gate`.
- Trend / history reports (`-historydir`) — would need a persisted location
  outside `target/`. Deferred.
- Wiring the rendered HTML into CI artifacts — deferred to the GitHub Actions
  pipeline; `./mvnw verify` already emits the JaCoCo XML CI consumes.
