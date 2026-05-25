# Quick Task 003 Summary — Postman collection auth automation

## Result

Recorded the Postman collection auth automation as a quick task under F-POSTMAN. No collection file
edits were needed in this task because the auth behavior was already present.

Tracked behavior:

- `accessToken` collection variable.
- Login request stores `access_token`.
- Collection-level pre-request script auto-logins with the demo user when `accessToken` is empty.
- Protected requests use `Authorization: Bearer {{accessToken}}`.

## Verification

- Collection JSON parses successfully.
- Structural auth checks pass against `docs/postman/invoice-generator.postman_collection.json`.

