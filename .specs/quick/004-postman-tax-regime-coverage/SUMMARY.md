# Quick Task 004 Summary — Postman tax-regime happy paths

## Result

Added two Postman happy-path requests:

- `POST /api/orders/generate-invoice — JURIDICA LUCRO_REAL`
- `POST /api/orders/generate-invoice — JURIDICA LUCRO_PRESUMIDO`

The happy-path folder now covers every valid `TaxRateTable` person/tax-regime variation:

- `FISICA`
- `JURIDICA + SIMPLES_NACIONAL`
- `JURIDICA + LUCRO_REAL`
- `JURIDICA + LUCRO_PRESUMIDO`

`JURIDICA + OUTROS` remains covered as a rejection path.

## Verification

- Collection JSON parses successfully.
- Structural coverage check found all four valid variations in the `Happy paths` folder.

