# Quick Task 001 — Postman collection for the invoice-generator API

**Scope:** ≤3 files of real content. One-line scope: ship a Postman v2.1.0 collection that
exercises every documented HTTP path of the service.

## Why

Reviewers and operators currently learn the API by reading `README.md` + running
hand-crafted `curl` commands. A Postman collection gives them a one-click way to:

- POST a valid invoice with both shipped sample payloads (FISICA + JURIDICA SIMPLES).
- Hit the legacy alias (`POST /api/pedido/gerarNotaFiscal`) and confirm it still works.
- Trigger each of the three documented HTTP 400 rejections
  (`UNSUPPORTED_TAX_REGIME`, `INVALID_TAX_REGIME`, `INVALID_DELIVERY_REGION`) and see the
  `codigo` / `mensagem` contract.
- Inject `X-Correlation-Id` so the F-OBSERVABILITY trace + log correlation is exercised
  on every request without thinking about it.

## What ships

- `docs/postman/invoice-generator.postman_collection.json` — collection (Postman v2.1.0).
- `docs/postman/README.md` — how to import + how the env var works.
- `.specs/project/ROADMAP.md` — new M3 entry `F-POSTMAN` marked COMPLETE.
- `.specs/quick/001-postman-collection/SUMMARY.md` — written after verify is green.

## Done when

- Collection JSON is valid (parses with `jq`).
- Each request has at least one `pm.test()` assertion: success-path tests check HTTP 200
  and the snake_case Portuguese keys; rejection tests check HTTP 400 and the expected
  `codigo`.
- `{{baseUrl}}` collection variable defaults to `http://localhost:8080`.
- ROADMAP shows F-POSTMAN COMPLETE under M3.
