# F-DEFECTS-PERFORMANCE — Kafka Async Dispatch Specification

## Problem Statement

`GenerateInvoiceInteractor` still calls four outbound ports synchronously after generating the invoice:

- `stockPort.sendInvoiceForStockDeduction(invoice)`
- `invoiceRegistrationPort.registerInvoice(invoice)`
- `deliveryPort.scheduleDelivery(invoice)`
- `accountsReceivablePort.sendInvoiceToAccountsReceivable(invoice)`

The adapter implementations use `Thread.sleep(...)` to simulate external service latency and outage risk. In this project, every such sleep represents an asynchronous downstream integration boundary. The service must not block the HTTP request while those external systems are slow or temporarily unavailable.

The target design is Kafka-backed asynchronous dispatch: invoice generation returns after the invoice is calculated and integration events are durably published. Kafka consumers call the downstream adapters and retry until the service becomes available or the message is routed to a dead-letter topic.

## Messaging Decision

The selected implementation for this feature is **Kafka**. This is an explicit project decision for the challenge roadmap: it exercises producer/consumer design, topic topology, partitions, consumer groups, retry topics, DLT handling, and local Docker Compose operation.

For a lean AWS production implementation of this exact scenario, **SQS would likely be the better default**. The four downstream actions are command-style side effects with one logical worker each: stock deduction, invoice registration, delivery scheduling, and accounts receivable. They mainly need decoupling, at-least-once delivery, retry, and dead-letter handling. Amazon SQS is a fully managed message queue for decoupling distributed application components, and it has native DLQ/redrive support. Kafka/MSK is stronger when the system needs streaming semantics, topic replay, long-lived event logs, multiple independent subscriber groups over the same event stream, or Kafka ecosystem compatibility.

Decision for this codebase: keep Kafka in scope now, but document SQS as the simpler AWS alternative for F-AWS if the goal becomes minimum operational complexity instead of demonstrating Kafka.

## Deployment Boundary

There are two intentional boundaries:

| Context | Publisher | Consumers | Reason |
| --- | --- | --- | --- |
| Technical test / local compose | This Spring Boot application | This same Spring Boot application | Demonstrates the full Kafka flow without creating four extra services. |
| Ideal production | `invoice-generator-service` only | Separate stock, fiscal registration, delivery, and accounts-receivable services | Each downstream domain owns its consumer, integration rules, idempotency, retry/DLT handling, and observability. |

In production, the invoice-generator service should not own the downstream business behavior. It should calculate the invoice, publish integration events, and expose enough correlation/status information for operators or clients to understand downstream progress.

## HTTP Response Semantics

`POST /api/orders/generate-invoice` still generates the invoice synchronously. The endpoint should:

1. Validate the request.
2. Calculate tax, freight, totals, and invoice items in the invoice-generator service.
3. Publish the four required Kafka integration events durably.
4. Return the generated invoice payload after event publication succeeds.

The response means: **invoice generated and downstream processing requested**.

The response does **not** mean: stock deduction, fiscal registration, delivery scheduling, and accounts-receivable posting have already completed.

For this challenge, keep the current JSON response shape unchanged unless the user explicitly approves a contract change. If the API can evolve later, a better contract would include explicit async status metadata, for example `processing_status=DISPATCHED`, integration statuses, or a status endpoint keyed by `invoiceId`/`orderId`.

## Kafka Topology

Use one command topic per downstream integration. This keeps ownership, scaling, retry policy, and dead-letter handling isolated per service.

| Topic | Partitions | Message key | Consumer | Consumer group |
| --- | ---: | --- | --- | --- |
| `invoice.stock-deduction.v1` | 3 | `invoiceId` or `orderId` | `StockDeductionConsumer` | `invoice-generator-stock-deduction` |
| `invoice.registration.v1` | 3 | `invoiceId` or `orderId` | `InvoiceRegistrationConsumer` | `invoice-generator-registration` |
| `invoice.delivery-scheduling.v1` | 3 | `invoiceId` or `orderId` | `DeliverySchedulingConsumer` | `invoice-generator-delivery-scheduling` |
| `invoice.accounts-receivable.v1` | 3 | `invoiceId` or `orderId` | `AccountsReceivableConsumer` | `invoice-generator-accounts-receivable` |

Each main topic has the same retry/DLT shape:

| Topic suffix | Purpose |
| --- | --- |
| `.retry.1m` | Short retry after transient failure. |
| `.retry.5m` | Intermediate retry. |
| `.retry.30m` | Long retry before dead-lettering. |
| `.dlt` | Exhausted failure for investigation and replay. |

Example for delivery:

```text
invoice.delivery-scheduling.v1
invoice.delivery-scheduling.v1.retry.1m
invoice.delivery-scheduling.v1.retry.5m
invoice.delivery-scheduling.v1.retry.30m
invoice.delivery-scheduling.v1.dlt
```

Start with 3 partitions per topic. That supports parallelism while preserving ordering for events with the same key. Revisit partition counts only when production throughput is known.

## Goals

- [ ] Move all four downstream integration calls off the HTTP request thread.
- [ ] Use Kafka as the durable asynchronous transport for stock, invoice registration, delivery, and accounts receivable.
- [ ] Preserve `Thread.sleep(...)` stubs as external-service simulations; do not delete sleeps to hide latency.
- [ ] Add retry with backoff for temporarily unavailable downstream services.
- [ ] Add dead-letter handling for exhausted retries.
- [ ] Make Kafka consumers idempotent so redelivery cannot duplicate side effects.
- [ ] Add a Dockerfile for the Spring Boot application.
- [ ] Add a docker-compose stack that starts Kafka and the application together.
- [ ] Make local compose startup enough to test HTTP invoice generation and Kafka consumer processing.
- [ ] Keep the invoice response contract unchanged.
- [ ] Document that the endpoint returns after invoice generation and event publication, before downstream completion.
- [ ] Prove `POST /api/orders/generate-invoice` no longer waits for the >5-item delivery sleep.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Real SEFAZ/stock/delivery/finance APIs | The current adapters remain stubs; Kafka changes the call boundary. |
| Full AWS MSK Terraform | Belongs in F-AWS after the local Kafka design is verified. |
| End-user notification of async completion | Future product feature; this task only guarantees durable dispatch/retry. |
| Removing `Thread.sleep` | Sleeps are the simulation trigger proving async/resilience behavior. |

---

## User Stories

### P1: Kafka dispatch for generated invoice side effects

**User Story:** As an API consumer, I want invoice generation to respond without waiting for stock, registration, delivery, or finance systems, so that slow downstream services do not make invoice generation slow.

**Acceptance Criteria:**

1. **WHEN** an invoice is generated **THEN** the application SHALL publish Kafka messages for stock deduction, invoice registration, delivery scheduling, and accounts receivable.
2. **WHEN** Kafka topics are provisioned/configured **THEN** the four main topics SHALL match the topology table above with 3 partitions each.
3. **WHEN** consumers are configured **THEN** each integration SHALL have its own consumer and consumer group as listed in the topology table.
4. **WHEN** the current direct port calls are searched in `GenerateInvoiceInteractor` **THEN** they SHALL not be invoked synchronously on the HTTP request path.
5. **WHEN** any adapter still contains `Thread.sleep(...)` **THEN** that sleep SHALL run only in the Kafka consumer processing path, not before the HTTP response is returned.
6. **WHEN** an order has more than 5 items **THEN** the HTTP request SHALL not wait for the 5000 ms delivery simulation.
7. **WHEN** the endpoint returns successfully **THEN** it SHALL mean invoice calculation completed and Kafka dispatch was accepted; it SHALL NOT claim downstream services have completed.

**Independent Test:**

```bash
./mvnw test -Dtest='GenerateInvoiceInteractorTest,*Kafka*Test'
./mvnw test -Pslow
```

---

### P1: Retry and dead-letter handling

**User Story:** As an operator, I want failed downstream calls to be retried and retained, so that temporary service outages do not lose invoice side effects.

**Acceptance Criteria:**

8. **WHEN** a downstream service is unavailable **THEN** the Kafka consumer SHALL retry with backoff instead of dropping the event.
9. **WHEN** retries are needed **THEN** the message SHALL flow through `<topic>.retry.1m`, `<topic>.retry.5m`, and `<topic>.retry.30m` according to the configured retry stage.
10. **WHEN** retries are exhausted **THEN** the event SHALL be sent to `<topic>.dlt` with enough metadata to investigate and replay.
11. **WHEN** the downstream service becomes available before retries are exhausted **THEN** the pending event SHALL be processed successfully.
12. **WHEN** the same Kafka event is delivered more than once **THEN** consumers SHALL handle it idempotently.

**Independent Test:**

```bash
./mvnw test -Dtest='*Kafka*Retry*Test,*Kafka*Dlq*Test,*Idempotency*Test'
```

---

### P2: Observability of async processing

**User Story:** As an operator, I want visibility into Kafka dispatch and consumer outcomes, so that async processing failures are diagnosable.

**Acceptance Criteria:**

13. **WHEN** events are published **THEN** logs SHALL include a correlation identifier linking the HTTP request, invoice, Kafka event, and consumer handling.
14. **WHEN** consumers process events **THEN** metrics SHALL expose success, failure, retry, DLQ, and consumer-lag signals per integration.
15. **WHEN** an event fails **THEN** logs SHALL identify the integration type and failure reason without dumping sensitive invoice data.

### P1: Local Docker stack for Kafka flow

**User Story:** As a developer, I want to start the application and Kafka with Docker Compose, so that the async flow can be tested locally without manual Kafka setup.

**Acceptance Criteria:**

16. **WHEN** the project is built as a container **THEN** a `Dockerfile` SHALL produce a runnable Spring Boot application image.
17. **WHEN** `docker compose up` is run **THEN** Kafka and the invoice-generator application SHALL start together.
18. **WHEN** the compose stack starts the application **THEN** it SHALL configure Kafka bootstrap servers for the Kafka service in the compose network.
19. **WHEN** the compose stack is fresh **THEN** the required main, retry, and DLT topics SHALL be created automatically or by a documented compose step.
20. **WHEN** the app is running through compose **THEN** `POST /api/orders/generate-invoice` SHALL be reachable on local port 8080 and publish Kafka integration events.

### P2: Production service ownership

**User Story:** As an architect, I want the production boundary to keep downstream concerns outside the invoice-generator service, so that each business capability owns its integration behavior.

**Acceptance Criteria:**

21. **WHEN** production architecture is documented **THEN** invoice-generator SHALL be described as publisher-only for the four integration events.
22. **WHEN** production consumers are documented **THEN** stock, fiscal registration, delivery, and accounts-receivable services SHALL own their own consumers.
23. **WHEN** the technical-test implementation is documented **THEN** same-codebase consumers SHALL be called out as a local/demo compromise, not the ideal production boundary.

## Requirement Traceability

| Requirement ID | Story | Status |
| --- | --- | --- |
| DEF-PERF-01 | P1 — Publish Kafka event for each downstream integration | Planned |
| DEF-PERF-02 | P1 — Four command topics use 3 partitions each | Planned |
| DEF-PERF-03 | P1 — One consumer and consumer group per downstream integration | Planned |
| DEF-PERF-04 | P1 — Remove synchronous direct calls from HTTP request path | Planned |
| DEF-PERF-05 | P1 — `Thread.sleep` runs only in async consumer path | Planned |
| DEF-PERF-06 | P1 — >5-item request no longer waits for delivery sleep | Planned |
| DEF-PERF-07 | P1 — HTTP success means invoice generated and Kafka dispatch accepted | Planned |
| DEF-PERF-08 | P1 — Retry unavailable downstream services with backoff | Planned |
| DEF-PERF-09 | P1 — Retry topics exist for 1m, 5m, and 30m stages | Planned |
| DEF-PERF-10 | P1 — Exhausted failures route to DLT | Planned |
| DEF-PERF-11 | P1 — Retry succeeds after service recovery | Planned |
| DEF-PERF-12 | P1 — Consumers are idempotent under redelivery | Planned |
| DEF-PERF-13 | P2 — Correlated logs across HTTP/Kafka/consumer | Planned |
| DEF-PERF-14 | P2 — Metrics cover success/failure/retry/DLQ/lag | Planned |
| DEF-PERF-15 | P2 — Failure logs are useful and data-safe | Planned |
| DEF-PERF-16 | P1 — Dockerfile builds runnable Spring Boot image | Planned |
| DEF-PERF-17 | P1 — Docker Compose starts Kafka and application | Planned |
| DEF-PERF-18 | P1 — Compose wires application Kafka bootstrap servers | Planned |
| DEF-PERF-19 | P1 — Compose creates or documents all required topics | Planned |
| DEF-PERF-20 | P1 — Compose exposes HTTP API and publishes Kafka events | Planned |
| DEF-PERF-21 | P2 — Production invoice-generator is publisher-only | Planned |
| DEF-PERF-22 | P2 — Production downstream services own consumers | Planned |
| DEF-PERF-23 | P2 — Same-codebase consumers documented as demo compromise | Planned |

## Success Criteria

- [ ] `GenerateInvoiceInteractor` calculates and returns the invoice without directly invoking the four external service ports.
- [ ] Kafka producer/consumer tests cover the four integration event types.
- [ ] Retry/DLQ tests prove an unavailable service is retried and not lost.
- [ ] `docker compose up --build` starts Kafka and the app locally.
- [ ] A local HTTP request through the compose stack publishes and consumes integration events.
- [ ] Slow characterization flips from "request takes >= 5000 ms" to "consumer may sleep, request remains under budget".
- [ ] `./mvnw verify` passes.
