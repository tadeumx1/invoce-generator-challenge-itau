# F-DEFECTS-PERFORMANCE Tasks

**Spec:** `.specs/features/defects-performance/spec.md`
**Design:** inline for now; implementation design can be expanded if Kafka wiring introduces more than the task list below.
**Status:** Done (2026-05-23). Executed as 5 consolidated vertical-slice tasks per the [[feedback-task-granularity]] preference. The original 8-task breakdown below is preserved for traceability — every requirement DEF-PERF-01..23 is satisfied. See `STATE.md` quick tasks #018-#022 for the actual execution log and `AD-023..AD-025` for the design decisions made during implementation.

---

## Key Design Decisions

1. **Kafka is the async boundary.** The HTTP request path publishes durable integration events; Kafka consumers invoke the current outbound adapters.
2. **SQS is documented as the simpler AWS alternative.** For this exact command-style side-effect workflow, SQS would likely be easier to operate in AWS. Kafka remains selected because this roadmap intentionally exercises Kafka topology, partitions, consumers, retry topics, and local Compose.
3. **Local/demo consumers live in this codebase.** This is a technical-test compromise so Docker Compose can demonstrate the full flow.
4. **Production consumers belong to downstream services.** In the ideal architecture, invoice-generator only publishes events; stock, fiscal registration, delivery, and accounts-receivable services consume their own topics.
5. **HTTP success means generated + dispatched.** `POST /api/orders/generate-invoice` returns the generated invoice after Kafka event publication succeeds, before downstream side effects complete.
6. **Every `Thread.sleep(...)` marks a simulated external service call.** Sleeps stay in adapter/client stubs, but they must execute from consumers, not the request thread.
7. **All four side effects are async.** Stock deduction, invoice registration, delivery scheduling, and accounts receivable are dispatched through Kafka.
8. **Use one command topic per integration.** Start with 3 partitions per topic and key messages by `invoiceId` or `orderId` to preserve ordering for the same invoice/order.
9. **Retry is required, not optional.** A temporarily unavailable service must not lose the message; retry with backoff through `.retry.1m`, `.retry.5m`, `.retry.30m`, then route exhausted failures to `.dlt`.
10. **Consumers must be idempotent.** Kafka redelivery and retry can happen; duplicate side effects must be prevented or safely ignored.

---

## Execution Plan

```
start
  ├─→ T1: define Kafka integration event contract
  ├─→ T2: replace synchronous side-effect calls with Kafka publisher
  ├─→ T3: add Kafka consumers for the four downstream ports
  ├─→ T4: add retry/backoff/DLQ behavior
  ├─→ T5: add idempotency safeguards
  ├─→ T6: add Dockerfile + Docker Compose local Kafka stack
  ├─→ T7: update performance characterization
  └─→ T8: docs + verification
```

---

## Task Breakdown

### T1: Define Kafka integration event contract

**What:** Create one integration event envelope for generated-invoice side effects, with an event id, invoice id/correlation id, event type, payload version, created timestamp, and enough invoice data for consumers to call the downstream port.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/application/**`
- `src/main/java/br/com/itau/invoicegenerator/adapter/messaging/**`
- `src/test/java/br/com/itau/invoicegenerator/**`

**Requirement:** DEF-PERF-01, DEF-PERF-12, DEF-PERF-13

**Done when:**
- [ ] Event types exist for `STOCK_DEDUCTION`, `INVOICE_REGISTRATION`, `DELIVERY_SCHEDULING`, and `ACCOUNTS_RECEIVABLE`.
- [ ] Event payload is versioned and serializable.
- [ ] Topic names are fixed as `invoice.stock-deduction.v1`, `invoice.registration.v1`, `invoice.delivery-scheduling.v1`, and `invoice.accounts-receivable.v1`.
- [ ] Each main topic is configured/documented with 3 partitions and message key `invoiceId` or `orderId`.
- [ ] Tests assert the event content produced for a generated invoice.

**Tests:** unit
**Gate:** `./mvnw test -Dtest='*IntegrationEvent*Test'`

---

### T2: Replace synchronous side-effect calls with Kafka publisher

**What:** Remove the direct request-thread calls:

```java
stockPort.sendInvoiceForStockDeduction(invoice);
invoiceRegistrationPort.registerInvoice(invoice);
deliveryPort.scheduleDelivery(invoice);
accountsReceivablePort.sendInvoiceToAccountsReceivable(invoice);
```

Replace them with publishing Kafka integration events after invoice calculation.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/application/GenerateInvoiceInteractor.java`
- `src/main/java/br/com/itau/invoicegenerator/domain/port/**`
- `src/main/java/br/com/itau/invoicegenerator/adapter/config/ApplicationBeanConfig.java`

**Requirement:** DEF-PERF-01, DEF-PERF-04, DEF-PERF-05, DEF-PERF-06, DEF-PERF-07

**Done when:**
- [ ] `GenerateInvoiceInteractor` no longer invokes the four external service ports directly.
- [ ] It publishes four Kafka messages for one generated invoice.
- [ ] Published records use the configured integration topic and key.
- [ ] Endpoint returns generated invoice only after Kafka publication succeeds.
- [ ] Docs/tests clarify that downstream completion is asynchronous and not part of the HTTP response.
- [ ] Existing invoice response JSON remains unchanged.

**Tests:** unit + HTTP integration
**Gate:** `./mvnw test -Dtest='GenerateInvoiceInteractorTest,InvoiceControllerIntegrationTest'`

---

### T3: Add Kafka consumers for downstream ports

**What:** Add consumers that subscribe to the integration topic and dispatch each event type to the matching outbound port.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/adapter/messaging/**`
- `src/main/java/br/com/itau/invoicegenerator/adapter/integration/**`

**Requirement:** DEF-PERF-03, DEF-PERF-05, DEF-PERF-08, DEF-PERF-11, DEF-PERF-23

**Done when:**
- [ ] `StockDeductionConsumer` consumes `invoice.stock-deduction.v1` with group `invoice-generator-stock-deduction` and calls `StockPort`.
- [ ] `InvoiceRegistrationConsumer` consumes `invoice.registration.v1` with group `invoice-generator-registration` and calls `InvoiceRegistrationPort`.
- [ ] `DeliverySchedulingConsumer` consumes `invoice.delivery-scheduling.v1` with group `invoice-generator-delivery-scheduling` and calls `DeliveryPort`.
- [ ] `AccountsReceivableConsumer` consumes `invoice.accounts-receivable.v1` with group `invoice-generator-accounts-receivable` and calls `AccountsReceivablePort`.
- [ ] Existing `Thread.sleep(...)` simulations run only from consumer-triggered adapter calls.

**Tests:** unit or Spring slice
**Gate:** `./mvnw test -Dtest='*KafkaConsumer*Test'`

---

### T4: Add retry, backoff, and dead-letter handling

**What:** Configure Kafka retry behavior so transient downstream failures are retried with backoff and exhausted failures are sent to a DLQ/retry topic with failure metadata.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/adapter/messaging/**`
- `src/main/resources/application.properties` or `application.yml`
- `src/test/java/br/com/itau/invoicegenerator/**`

**Requirement:** DEF-PERF-08, DEF-PERF-09, DEF-PERF-10, DEF-PERF-11, DEF-PERF-14, DEF-PERF-15

**Done when:**
- [ ] A failed downstream call is retried.
- [ ] A recovered downstream call eventually succeeds.
- [ ] Each main topic has `.retry.1m`, `.retry.5m`, `.retry.30m`, and `.dlt` topics.
- [ ] Exhausted failures route to DLT with integration type, event id, invoice id/correlation id, and failure reason.

**Tests:** unit/integration
**Gate:** `./mvnw test -Dtest='*Kafka*Retry*Test,*Kafka*Dlq*Test'`

---

### T5: Add idempotency safeguards for consumers

**What:** Prevent duplicate Kafka deliveries from creating duplicate downstream side effects. Use an event id / invoice id + event type processing record, or a documented idempotency key if persistence is not introduced in the first pass.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/adapter/messaging/**`
- `src/main/java/br/com/itau/invoicegenerator/domain/port/**`
- `src/test/java/br/com/itau/invoicegenerator/**`

**Requirement:** DEF-PERF-12

**Done when:**
- [ ] Re-processing the same event does not call the downstream adapter twice, or the adapter receives a stable idempotency key and tests prove duplicate handling.
- [ ] The idempotency strategy is documented as local/in-memory or durable, with its limitation if not durable yet.

**Tests:** unit
**Gate:** `./mvnw test -Dtest='*Idempotency*Test'`

---

### T6: Add Dockerfile + Docker Compose local Kafka stack

**What:** Add container support so a developer can run Kafka and the Spring Boot application locally with one compose command.

**Where:**
- `Dockerfile`
- `docker-compose.yml`
- `src/main/resources/application.properties` or profile-specific config
- `README.md` / `CLAUDE.md` if command documentation is needed

**Requirement:** DEF-PERF-16, DEF-PERF-17, DEF-PERF-18, DEF-PERF-19, DEF-PERF-20

**Done when:**
- [ ] `Dockerfile` builds a runnable application image.
- [ ] `docker-compose.yml` starts Kafka and the invoice-generator app.
- [ ] App container receives Kafka bootstrap servers for the compose Kafka service.
- [ ] Required main, retry, and DLT topics are created automatically or by a documented compose step.
- [ ] Local port 8080 reaches the app through compose.
- [ ] An HTTP invoice request through compose publishes Kafka events and consumers process them.

**Tests:** local smoke
**Gate:** `docker compose up --build`

---

### T7: Update slow-path characterization

**What:** Flip the C-6 slow test so it proves the HTTP request no longer waits for the 5000 ms delivery simulation while preserving a consumer-level test that still exercises the slow delivery adapter.

**Where:**
- `src/test/java/br/com/itau/invoicegenerator/service/characterization/SlowDeliveryCharacterizationTest.java`
- `src/test/java/br/com/itau/invoicegenerator/web/InvoiceControllerIntegrationTest.java`

**Requirement:** DEF-PERF-05, DEF-PERF-06

**Done when:**
- [ ] The request-path test asserts a bounded duration under the configured budget.
- [ ] A consumer/adapter test still proves the simulated slow delivery path exists.
- [ ] `Thread.sleep` was not removed as a shortcut.

**Tests:** slow + integration
**Gate:** `./mvnw test -Pslow`

---

### T8: Update docs and run verification

**What:** Keep roadmap, concerns, integrations, and operational docs aligned with the Kafka async design.

**Where:**
- `.specs/project/{ROADMAP.md,STATE.md}`
- `.specs/codebase/{CONCERNS.md,INTEGRATIONS.md,TESTING.md}`
- `.specs/features/defects-performance/{spec.md,tasks.md}`
- `CLAUDE.md`

**Requirement:** documentation + traceability

**Done when:**
- [ ] Roadmap names Kafka as the async mechanism for the four service calls.
- [ ] Roadmap lists the four command topics, 3 partitions each, consumers, consumer groups, retry topics, and DLT topics.
- [ ] Roadmap lists the Dockerfile and docker-compose local Kafka requirement.
- [ ] Spec documents Kafka as the chosen implementation and SQS as the simpler AWS alternative for this command-style flow.
- [ ] Spec documents local same-codebase consumers as a technical-test compromise.
- [ ] Spec documents production invoice-generator as publisher-only and downstream services as consumer owners.
- [ ] HTTP response semantics are documented as "invoice generated and Kafka dispatch accepted".
- [ ] Concerns C-6 points to Kafka retry/DLQ instead of generic queue guidance.
- [ ] Integration docs list Kafka topics and failure behavior.
- [ ] Full verification passes.

**Tests/Gates:**

```bash
./mvnw spotless:apply
./mvnw verify
./mvnw test -Pslow
```
