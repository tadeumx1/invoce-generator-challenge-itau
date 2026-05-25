# Quick Task 002 — Docker Compose healthcheck uses public Actuator health

**Milestone:** M5 — Hardening & DX polish.
**Scope:** one runtime config file plus roadmap tracking.

## Why

The Docker Compose app healthcheck was probing `GET /api/orders/generate-invoice`, which is a
protected mutating endpoint that only accepts authenticated `POST` requests. The probe produced
Spring Security `AccessDeniedException` traces in Jaeger even while real authenticated Postman
requests returned `200`.

## What ships

- `docker-compose.yml` — probe `GET /actuator/health`, a public endpoint explicitly permitted by
  `SecurityConfig`.
- `.specs/project/ROADMAP.md` — M5 records the completed healthcheck DX fix.
- `.specs/quick/002-compose-healthcheck/SUMMARY.md` — verification summary.

## Done when

- Compose healthcheck no longer calls `/api/orders/generate-invoice`.
- Compose healthcheck fails closed (`exit 1`) if `/actuator/health` is unavailable.
- `docker compose config` parses successfully.
- Roadmap M5 includes the completed task.
