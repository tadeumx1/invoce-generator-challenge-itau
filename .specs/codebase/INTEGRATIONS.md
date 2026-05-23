# External Integrations

## Current state

There are **no real external integrations** in the codebase today. Every "integration" is an in-process Java class that calls `Thread.sleep` to simulate latency. The README explicitly states this is intentional: the `sleep`s are part of the challenge premise (simulating slow upstream systems) and must not simply be deleted.

That said, the names in the code identify the **intended** integration domains. They are documented below so the refactor (F-CLEAN, F-RESILIENCE, F-OBSERVABILITY) can stand them up as real outbound adapters with proper contracts, timeouts, retries, and observability.

---

## Stock Deduction

**Service:** `StockService` (legacy stub)
**Purpose:** Notify a stock system that the items on the invoice should be deducted from inventory.
**Implementation:** `service/impl/StockService.java` — `Thread.sleep(380)`, no payload sent anywhere.
**Configuration:** none.
**Authentication:** none.
**Latency budget (legacy):** 380 ms.
**Target:** outbound adapter behind a `StockPort` interface in the domain layer. Likely async (event-published) rather than request/response.

## Invoice Registration

**Service:** `RegistrationService` (legacy stub)
**Purpose:** Register the invoice with the fiscal registry (real-world: SEFAZ / state revenue agency).
**Implementation:** `service/impl/RegistrationService.java` — `Thread.sleep(500)`.
**Configuration:** none.
**Authentication:** none.
**Latency budget (legacy):** 500 ms.
**Target:** outbound adapter behind `InvoiceRegistrationPort`. In production this is a *synchronous critical path* (the registry must accept the invoice for the response to be valid) — likely needs retry + circuit breaker.

## Delivery Scheduling

**Service / port:** `DeliveryService` + `DeliveryIntegrationPort` (legacy stubs; the port already exists in `port/out/`)
**Purpose:** Schedule the physical delivery of the order.
**Implementation:** `service/impl/DeliveryService.java` calls `port/out/DeliveryIntegrationPort.createDeliverySchedule(invoice)`. Sleep budgets: 150 ms in the service, then 200 ms in the port, **plus 5000 ms when `invoice.items.size() > 5`**.
**Configuration:** none.
**Authentication:** none.
**Latency budget (legacy):** 350 ms baseline, **5350 ms for orders > 5 items**.
**Target:** the +5s on >5 items is the README's `pedidos com mais de 6 itens ficam muito lentos` issue. The fix is **not** to remove the sleep but to handle it: dispatch the delivery scheduling asynchronously (e.g., publish to SQS / outbox), respond immediately, and reconcile via the delivery system's webhook.

## Accounts Receivable

**Service:** `FinanceService` (legacy stub)
**Purpose:** Send the invoice to the finance / accounts-receivable system so it can bill the customer.
**Implementation:** `service/impl/FinanceService.java` — `Thread.sleep(250)`.
**Configuration:** none.
**Authentication:** none.
**Latency budget (legacy):** 250 ms.
**Target:** outbound adapter behind `AccountsReceivablePort`. Async via event/outbox is appropriate — finance does not block invoice issuance.

---

## Webhooks

None today. Likely needed in the target state for the delivery system to call back with scheduling confirmations after async dispatch.

## Background Jobs

None today. The README's mention of "filas ou processamento assíncrono" anticipates introducing a queue (SQS) and worker(s) to handle the slow integrations off the request path.

## Future external services (per README's "Proposta de arquitetura")

| Category             | Likely choice in AWS                                         | Captured in feature |
| -------------------- | ------------------------------------------------------------ | ------------------- |
| API Gateway          | Amazon API Gateway (HTTP API) or Application Load Balancer  | F-AWS               |
| AuthN / AuthZ        | Cognito or JWT verifier in the gateway                       | F-AWS               |
| Async messaging      | SQS for outbound side-effects + outbox pattern               | F-RESILIENCE        |
| Resilience           | Resilience4j (circuit breaker, retry, timeout) on adapters   | F-RESILIENCE        |
| Observability — logs | CloudWatch Logs                                              | F-OBSERVABILITY     |
| Observability — metrics | CloudWatch Metrics + Micrometer                           | F-OBSERVABILITY     |
| Observability — tracing | AWS X-Ray (via OpenTelemetry)                             | F-OBSERVABILITY     |
| Compute              | ECS Fargate or Lambda (TBD per F-AWS)                        | F-AWS               |
| IaC                  | Terraform (default — see `STATE.md` AD-005) or CDK           | F-AWS               |
