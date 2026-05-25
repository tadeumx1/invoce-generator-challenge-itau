# Quick Task 004 — Postman happy paths cover every valid tax regime

**Feature:** F-POSTMAN.
**Scope:** Postman collection coverage update.

## Why

The happy-path folder only covered `FISICA` and `JURIDICA + SIMPLES_NACIONAL`. The domain
`TaxRateTable` also accepts `JURIDICA + LUCRO_REAL` and `JURIDICA + LUCRO_PRESUMIDO`, while
`JURIDICA + OUTROS` remains a rejection path.

## What ships

- Add happy-path request for `JURIDICA + LUCRO_REAL`.
- Add happy-path request for `JURIDICA + LUCRO_PRESUMIDO`.
- Keep `FISICA`, `JURIDICA + SIMPLES_NACIONAL`, and the legacy alias happy paths.
- Update the collection description to state that every valid person/tax-regime variation is
  covered.
- Update the roadmap F-POSTMAN entry.

## Done when

- The `Happy paths` folder contains `FISICA`, `SIMPLES_NACIONAL`, `LUCRO_REAL`, and
  `LUCRO_PRESUMIDO` coverage.
- `OUTROS` remains under rejection coverage.
- Collection JSON parses successfully.
- Roadmap F-POSTMAN references this quick task.

