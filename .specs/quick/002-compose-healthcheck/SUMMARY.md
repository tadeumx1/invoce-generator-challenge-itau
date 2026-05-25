# Quick Task 002 Summary — Docker Compose healthcheck

## Result

Updated the `invoice-generator` service healthcheck to call the public Actuator health endpoint:

```text
GET /actuator/health
```

This avoids generating misleading Spring Security `AccessDeniedException` traces in Jaeger from
unauthenticated `GET /api/orders/generate-invoice` probes.

## Verification

- `docker compose config` parses successfully.

