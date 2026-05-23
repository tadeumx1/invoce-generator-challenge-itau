# External Integrations

## Current State

There are no real external integrations. Each integration is an in-process adapter that implements a domain port and calls `Thread.sleep` to simulate latency. The sleeps are intentionally preserved; later features must handle them with Kafka-backed async processing, retries, timeouts, and observability rather than deleting them.

Project premise: every `Thread.sleep(...)` site in an integration adapter/client represents a simulated external asynchronous service call. The production-shaped flow should publish a Kafka message and process the external call in a consumer with retry/DLQ behavior.

Messaging decision: Kafka is selected for this project implementation. For a lean AWS production version of the same command-style workflow, SQS would likely be simpler operationally because each downstream action has one logical worker and mainly needs decoupling, retry, and DLQ rather than stream replay or multiple subscriber groups.

Local/demo boundary: this repository will contain both publisher and consumers so the technical test can demonstrate the full flow with Docker Compose.

Production boundary: invoice-generator should only publish events. Separate stock, fiscal registration, delivery, and accounts-receivable services should own their consumers and downstream integration rules.

HTTP boundary: `POST /api/orders/generate-invoice` returns after the invoice is calculated and the integration events are published. A successful response means "invoice generated and downstream processing requested"; it does not mean all downstream side effects are complete.

## Stock Deduction

**Port:** `domain/port/StockPort`
**Adapter:** `adapter/integration/stock/StockIntegrationAdapter`
**Purpose:** Notify stock that invoice items should be deducted.
**Legacy latency:** 380 ms.
**Target:** Kafka async outbound processing with retry/DLQ and idempotent stock deduction.

## Invoice Registration

**Port:** `domain/port/InvoiceRegistrationPort`
**Adapter:** `adapter/integration/registration/InvoiceRegistrationAdapter`
**Purpose:** Register the invoice with the fiscal registry.
**Legacy latency:** 500 ms.
**Target:** Kafka async outbound processing with retry/DLQ and idempotent fiscal registration.

## Delivery Scheduling

**Port:** `domain/port/DeliveryPort`
**Adapter:** `adapter/integration/delivery/DeliveryIntegrationAdapter`
**Client stub:** `adapter/integration/delivery/DeliverySchedulingClient`
**Purpose:** Schedule physical delivery.
**Legacy latency:** 150 ms adapter + 200 ms client, plus 5000 ms when `invoice.items.size() > 5`.
**Target:** Kafka async dispatch; the +5s path is C-6 and should move off the request thread while remaining exercised in consumer/adapter tests.

## Accounts Receivable

**Port:** `domain/port/AccountsReceivablePort`
**Adapter:** `adapter/integration/finance/AccountsReceivableAdapter`
**Purpose:** Notify finance/accounts receivable.
**Legacy latency:** 250 ms.
**Target:** Kafka async outbound processing with retry/DLQ and idempotent accounts-receivable posting.

## Target Kafka Topics

Use one command topic per integration. Each main topic starts with 3 partitions and uses `invoiceId` or `orderId` as the message key.

| Topic | Partitions | Producer | Consumer | Consumer group |
| --- | ---: | --- | --- | --- |
| `invoice.stock-deduction.v1` | 3 | invoice generation messaging adapter | `StockDeductionConsumer` | `invoice-generator-stock-deduction` |
| `invoice.registration.v1` | 3 | invoice generation messaging adapter | `InvoiceRegistrationConsumer` | `invoice-generator-registration` |
| `invoice.delivery-scheduling.v1` | 3 | invoice generation messaging adapter | `DeliverySchedulingConsumer` | `invoice-generator-delivery-scheduling` |
| `invoice.accounts-receivable.v1` | 3 | invoice generation messaging adapter | `AccountsReceivableConsumer` | `invoice-generator-accounts-receivable` |

Each main topic also has retry/DLT topics:

- `<topic>.retry.1m`
- `<topic>.retry.5m`
- `<topic>.retry.30m`
- `<topic>.dlt`

## Local Docker Stack

F-DEFECTS-PERFORMANCE must add:

- `Dockerfile` for the Spring Boot application.
- `docker-compose.yml` that starts Kafka and the invoice-generator application.
- Application Kafka bootstrap configuration pointing to the compose Kafka service.
- Automated or documented creation of the four main topics plus retry/DLT topics.
- Local HTTP access on port 8080 for smoke testing invoice generation and Kafka dispatch.

## Future External Services

| Category | Likely choice in AWS | Captured in feature |
| --- | --- | --- |
| API Gateway | Amazon API Gateway HTTP API or ALB | F-AWS |
| AuthN / AuthZ | Cognito or JWT verifier at gateway | F-AWS |
| Async messaging | Kafka / Amazon MSK with retry + DLQ topics | F-DEFECTS-PERFORMANCE, F-RESILIENCE |
| Resilience | Resilience4j on adapters | F-RESILIENCE |
| Logs | CloudWatch Logs | F-OBSERVABILITY |
| Metrics | Micrometer + CloudWatch Metrics | F-OBSERVABILITY |
| Tracing | OpenTelemetry + AWS X-Ray | F-OBSERVABILITY |
| IaC | Terraform by default | F-AWS |
