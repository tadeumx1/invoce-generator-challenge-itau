# Concerns

Actionable warnings about the codebase. Each entry has evidence (file:line), impact, and a proposed fix. Ranked by risk × likelihood × user-visibility.

> All concerns here track to features in `ROADMAP.md`. The README itself flags most of them as challenge tasks.

---

## C-1 — Singleton accumulating list in `LegacyProductTaxRateCalculator` ✅ resolved in F-DEFECTS-FUNCTIONAL

**Previous evidence:** `domain/service/LegacyProductTaxRateCalculator.java` kept a mutable request list on a singleton bean.
**Previous impact:** Every request appended its items to the same list and returned a cumulative item list, creating cross-request data leakage.
**Resolution:** `LegacyProductTaxRateCalculator` is now stateless. Each `calculateTax` call maps its input items to a fresh result list.
**Regression coverage:** `LegacyProductTaxRateCalculatorTest` and `StaticListAccumulationCharacterizationTest` assert request isolation.
**Residual risk:** none for C-1.

---

## C-2 — Missing branch for `taxRegime = OUTROS` and `taxRegime = null` ✅ resolved in F-DEFECTS-FUNCTIONAL

**Previous evidence:** `domain/service/TaxRateTable.java` returned no rate for `JURIDICA + OUTROS` and `JURIDICA + null`, so the invoice could return `items: []` while preserving `totalItemsValue`.
**Resolution:** `TaxRateTable` now rejects invalid juridica tax regimes with `InvalidInvoiceOrderException`. `OUTROS` maps to `UNSUPPORTED_TAX_REGIME`; null maps to `INVALID_TAX_REGIME`.
**HTTP behavior:** `ApiExceptionHandler` maps the domain exception to HTTP 400 with JSON keys `codigo` and `mensagem`.
**Regression coverage:** `UnhandledTaxRegimeCharacterizationTest` and `InvoiceControllerIntegrationTest`.
**Residual risk:** none for C-2.

---

## C-3 — Freight is broken when no ENTREGA address has a usable region ✅ resolved in F-DEFECTS-FUNCTIONAL

**Previous evidence:** missing delivery address and delivery address with `region=null` silently produced adjusted freight `0.0`. Earlier F-SAFETY-NET characterization also discovered an accidental `Stream.findFirst()` NPE for null region; F-CLEAN removed that accidental exception path before the final policy was chosen.
**Previous impact:** malformed addresses could generate invoices with zero freight.
**Resolution:** `LegacyFreightCalculator` now rejects both cases with `InvalidInvoiceOrderException` code `INVALID_DELIVERY_REGION`.
**HTTP behavior:** the web adapter maps this to HTTP 400 with JSON keys `codigo` and `mensagem`.
**Regression coverage:** `MissingRegionFreightCharacterizationTest` and `InvoiceControllerIntegrationTest`.
**Residual risk:** none for C-3.

---

## C-4 — Money handled as `double` ✅ resolved in F-DEFECTS-FUNCTIONAL

**Previous evidence:** monetary fields in `Order`, `Invoice`, `Item`, and `InvoiceItem` used `double`.
**Previous impact:** tax and freight arithmetic could expose floating-point artifacts to the domain and downstream integrations.
**Resolution:** domain models and web DTOs now use `BigDecimal` for money. Calculated item tax and freight go through `Money.rounded`, using scale 2 and `RoundingMode.HALF_EVEN`. Tax rates are represented as `BigDecimal` via `TaxRate`.
**Wire contract:** JSON values remain numeric; field names and enum values are unchanged.
**Residual risk:** none for C-4. Business may still revisit quantity handling separately.

---

## C-5 — Tests declared mocks that never applied ✅ resolved in F-SAFETY-NET / F-CLEAN

**Previous evidence:** the starter test declared `@Mock ProductTaxRateCalculator` and `@InjectMocks InvoiceGeneratorServiceImpl`, but the SUT instantiated the calculator internally.
**Resolution:** Tests now use `GenerateInvoiceInteractorTest` with `RecordingTaxRateCalculator` and explicit use-case construction. No current tests import Mockito, and Mockito is excluded from `spring-boot-starter-test`.
**Residual risk:** none for this concern.

---

## C-6 — Side-effects run synchronously on the request thread ✅ resolved in F-DEFECTS-PERFORMANCE

**Previous evidence:** `application/GenerateInvoiceInteractor.java` invoked four outbound ports synchronously; adapters in `adapter/integration/**` still `Thread.sleep`.
**Previous latency budget:** 380 + 500 + 150 + 200 + 250 = **1480 ms** for any order; **+5000 ms** more when `items.size() > 5`.
**Resolution:** `GenerateInvoiceInteractor` now depends on a single `InvoiceSideEffectDispatcher` port. The Kafka-backed implementation publishes four integration events (one per topic) and returns. Four `@KafkaListener` consumers in `adapter/integration/{stock,registration,delivery,finance}` call the existing port adapters. The 5-second delivery sleep stays on the consumer thread.
**Regression coverage:** `InvoiceKafkaFlowIntegrationTest` uses EmbeddedKafka end-to-end and asserts the HTTP path returns under 3 s, plus all four consumers process the events.
**Retry / DLT:** Spring Kafka's `@RetryableTopic` on each consumer (4 attempts, exponential backoff) routes transient failures to retry topics and exhausted failures to a `-dlt` topic. AD-023 documents the timing.
**Idempotency:** in-memory `IdempotencyStore` keyed on `(topic, eventId)` deduplicates Kafka redelivery. Non-durable; AD-024 records the production-rollout caveat.
**Observability hook:** the "no observability into which leg was slow" half of this concern is wired by **F-OBSERVABILITY** (SLI-4 side-effect end-to-end timer producer→consumer-ack; per-topic dispatch/retry/DLT counters; consumer-lag gauge; HTTP→Kafka trace propagation).
**Residual risk:** `IdempotencyStore` is in-memory. A process restart drops the dedupe set and could allow Kafka redelivery to double-execute a side effect. Tracked as a deferred idea.

---

## C-7 — Misspelled resource directory `paylods/` ✅ resolved (2026-05-23)

**Previous evidence:** `src/main/resources/paylods/teste-pf.json`, `teste-pj-simples.json`.
**Previous impact:** Confusion when looking for sample payloads; misleading file path in docs.
**Resolution (2026-05-23):** directory renamed `paylods/` → `payloads/`. Every doc, spec, test fixture loader, Postman collection, and CI workflow reference swept in the same commit. `./mvnw test` green; no remaining `paylods` substring anywhere in the repo.

---

## C-8 — `InterruptedException` discards interrupt flag ✅ resolved in F-RESILIENCE

**Previous evidence:** Every `Thread.sleep` site in `adapter/integration/**`.
**Previous pattern:** `catch (InterruptedException e) { throw new RuntimeException(e); }`
**Previous impact:** Lost interrupt flag; downstream code in pools (executors, Kafka consumer thread pools) could not shut down cleanly.
**Resolution:** F-RESILIENCE T1 (2026-05-23) introduced `IntegrationAdapterException` (typed `RuntimeException` carrying the integration name) and rewrote every `Thread.sleep` catch block in `adapter/integration/**` to call `Thread.currentThread().interrupt();` before rethrowing as `IntegrationAdapterException`. See AD-028 in `STATE.md`.
**Regression coverage:** `CircuitBreakerLifecycleTest` exercises `IntegrationAdapterException` through the Resilience4j circuit breaker to confirm the exception class flows correctly.
**Residual risk:** none for C-8. A future style convention in `CONVENTIONS.md` ("new adapter sleep sites must preserve the interrupt flag and throw `IntegrationAdapterException`") would help avoid regressions.

---

## C-9 — Missing test coverage ✅ resolved in F-SAFETY-NET

**Previous evidence:** the starter project had two flawed tests and no coverage for bracket edges, freight, fallthrough behavior, slow delivery, or HTTP.
**Resolution:** The fast suite now has 56 tests; the slow characterization runs via `./mvnw test -Pslow`. Coverage includes tax brackets, freight multipliers, C-1/C-2/C-3/C-6 characterization/regression, HTTP contract, typed 400 responses, and Spring context wiring.
**Residual risk:** JaCoCo is report-only; no coverage threshold is enforced yet.

---

## C-10 — Pre-existing Lombok / JDK 16+ build break ✅ resolved in F-UPGRADE

**Evidence:** Spring Boot 2.6.2 → Lombok 1.18.22 → fails on JDK 16+ with `NoSuchFieldError: Class com.sun.tools.javac.tree.JCTree$JCImport does not have member field 'com.sun.tools.javac.tree.JCTree qualid'`.
**Impact:** Before F-UPGRADE, builds only succeeded under JDK 11.
**Fix:** F-UPGRADE moved the project to Java 21 + Spring Boot 3.5.14. `./mvnw verify` passes on the default JDK 21 shell.
