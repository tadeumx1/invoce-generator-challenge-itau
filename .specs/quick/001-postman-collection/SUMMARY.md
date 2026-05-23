# Quick Task 001 — Postman collection — SUMMARY

**Closed:** 2026-05-23
**Status:** Done

## What shipped

- `docs/postman/invoice-generator.postman_collection.json` — Postman v2.1.0 collection,
  2 folders, 6 requests (3 happy paths + 3 rejections), every request with `pm.test()`
  assertions and an `X-Correlation-Id` header to exercise F-OBSERVABILITY correlation.
- `docs/postman/README.md` — import instructions for Postman + `npx newman`, request
  matrix, maintenance note.
- `.specs/project/ROADMAP.md` — new `F-POSTMAN` entry under M3, marked COMPLETE.

## Verification

- `jq -e '.info.schema'` returns the v2.1.0 schema URL.
- `jq -r '.item[].item[] | .request.body.raw' … | jq -e 'type'` returns `"object"`
  for all 6 requests — every embedded body is valid JSON.
- All 6 requests are listed by name in `jq -r '[.item[].item[] | .name]'`.

## Notes for future work

- If the `paylods/` → `payloads/` rename (C-7 in `.specs/codebase/CONCERNS.md`) ever
  happens, only the collection's request descriptions need to change — the embedded
  bodies are self-contained and don't reference filesystem paths.
- When a new rejection code is added to `RejectionCode`, add a matching Postman request
  in the "Rejections (HTTP 400)" folder in the same commit.
- A future improvement is to add a CI step that runs the collection against the booted
  app via Newman — kept out of scope here because it would couple the docs/ directory
  to the Maven verify gate. Captured as a deferred idea below.

## Deferred

- Wire `npx newman run docs/postman/…` into the CI pipeline once the project has CI.
- Generate an HTML report from a Newman run and attach to PR artifacts.
