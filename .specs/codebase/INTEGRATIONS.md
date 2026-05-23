# External Integrations

## Current State

There are no real external integrations. Each integration is an in-process adapter that implements a domain port and calls `Thread.sleep` to simulate latency. The sleeps are intentionally preserved; later features must handle them with async processing, timeouts, retries, and observability rather than deleting them.

## Stock Deduction

**Port:** `domain/port/StockPort`
**Adapter:** `adapter/integration/stock/StockIntegrationAdapter`
**Purpose:** Notify stock that invoice items should be deducted.
**Legacy latency:** 380 ms.
**Target:** async outbound processing via outbox/queue.

## Invoice Registration

**Port:** `domain/port/InvoiceRegistrationPort`
**Adapter:** `adapter/integration/registration/InvoiceRegistrationAdapter`
**Purpose:** Register the invoice with the fiscal registry.
**Legacy latency:** 500 ms.
**Target:** likely remains synchronous but with timeout, retry, circuit breaker, and clear failure semantics.

## Delivery Scheduling

**Port:** `domain/port/DeliveryPort`
**Adapter:** `adapter/integration/delivery/DeliveryIntegrationAdapter`
**Client stub:** `adapter/integration/delivery/DeliverySchedulingClient`
**Purpose:** Schedule physical delivery.
**Legacy latency:** 150 ms adapter + 200 ms client, plus 5000 ms when `invoice.items.size() > 5`.
**Target:** async dispatch; the +5s path is C-6 and should move off the request thread.

## Accounts Receivable

**Port:** `domain/port/AccountsReceivablePort`
**Adapter:** `adapter/integration/finance/AccountsReceivableAdapter`
**Purpose:** Notify finance/accounts receivable.
**Legacy latency:** 250 ms.
**Target:** async outbound processing via outbox/queue.

## Future External Services

| Category | Likely choice in AWS | Captured in feature |
| --- | --- | --- |
| API Gateway | Amazon API Gateway HTTP API or ALB | F-AWS |
| AuthN / AuthZ | Cognito or JWT verifier at gateway | F-AWS |
| Async messaging | SQS + outbox pattern | F-RESILIENCE |
| Resilience | Resilience4j on adapters | F-RESILIENCE |
| Logs | CloudWatch Logs | F-OBSERVABILITY |
| Metrics | Micrometer + CloudWatch Metrics | F-OBSERVABILITY |
| Tracing | OpenTelemetry + AWS X-Ray | F-OBSERVABILITY |
| IaC | Terraform by default | F-AWS |
