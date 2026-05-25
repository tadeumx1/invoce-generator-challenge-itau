# Quick Task 003 — Postman collection auth automation

**Feature:** F-POSTMAN.
**Scope:** track the Postman collection auth changes already present in the repo.

## Why

After F-AUTH, every invoice-generation request requires a JWT with `invoice:write`. The Postman
collection was updated so reviewers can still run the productive and rejection-path requests
without manually calling login first.

## What changed

- `docs/postman/invoice-generator.postman_collection.json` defines an `accessToken` collection
  variable.
- The login request stores `access_token` into `accessToken`.
- A collection-level pre-request script auto-runs `POST /api/auth/login` as `demo`/`demo123` when
  `accessToken` is empty.
- Invoice and rejection requests send `Authorization: Bearer {{accessToken}}`.
- Auth-specific requests cover successful login, wrong-password `401`, and rate-limit behavior.

## Done when

- Collection JSON parses successfully.
- Collection has an `accessToken` variable.
- Collection has the auto-login pre-request script.
- Protected invoice/rejection requests use `Authorization: Bearer {{accessToken}}`.
- Roadmap F-POSTMAN references this quick task.

