# F-CLEAN Tasks

**Spec:** `.specs/features/clean/spec.md`
**Design:** skipped as a separate file — the design is the Clean Architecture package split described inline here and in `.specs/codebase/ARCHITECTURE.md`.
**Status:** Done (2026-05-23)

---

## Key Design Decisions (Rolled In From Skipped Design Phase)

1. **Use `domain/application/adapter` package names.** This keeps the Java package tree aligned with Clean Architecture vocabulary and avoids inventing project-specific layer names.
2. **Keep `domain` and `application` framework-free.** Spring and Jackson are adapter concerns. Domain/application are verified by import scans.
3. **Use explicit DTO mapping at the web boundary.** The old model classes carried `@JsonProperty`; F-CLEAN moves those annotations to `adapter/web/dto` so domain models are not transport DTOs.
4. **Preserve legacy defects with `Legacy*` names.** `LegacyProductTaxRateCalculator` and `LegacyFreightCalculator` make it obvious that behavior is intentionally preserved until M2.
5. **Use Java language constructs for simple rule dispatch.** Freight multiplier selection is clearer as a `switch` over `Region` than as a long `if/else if` chain.
6. **Use reusable bracket tables for tax rates.** The repeated tax-rate `if/else` chains are clearer as data (`TaxRateBracket[]`) plus a small lookup helper.
7. **Make delivery-region extraction null-safe without deciding final policy.** Mapping after `findFirst()` on `Address` avoids `Stream.findFirst()` NPE on null regions. The fallback remains `0.0` until M2 defines reject-vs-pass-through behavior.
8. **Keep side effects synchronous for now.** F-CLEAN only moves them behind ports. There is no fire-and-forget implementation today. Async/outbox/resilience belongs to later roadmap items, and production work should prefer durable queues over detached threads.
9. **Remove Mockito from tests.** Once the interactor is directly constructible, explicit fakes are simpler and avoid restricted-JVM issues.

---

## Execution Plan

### Phase 1: Layer Skeleton and Domain Move

```
start -> T1 -> T2 -> T3
```

Move models first, then add ports/services, then compile to catch package drift.

### Phase 2: Use Case and Adapters

```
T3 -> T4 -> T5 -> T6
```

Introduce the application interactor, then adapters, then Spring composition and HTTP controller changes.

### Phase 3: Test Migration and Verification

```
T6 -> T7 -> T8 -> T9
```

Update tests to the new graph, remove Mockito, run all gates.

### Phase 4: Documentation

```
T9 -> T10 -> T11 -> T12
```

Update spec-driven docs only after implementation and verification are stable. T11 captures the follow-up review feedback on freight branching/null safety and async guidance. T12 captures the same cleanup for tax-rate conditions.

---

## Task Breakdown

### T1: Move domain models and enums to `domain/model`

**What:** Move all former `model/*` classes and enums into `br.com.itau.invoicegenerator.domain.model`. Update production and test imports.

**Where:**
- delete old package files under `src/main/java/br/com/itau/invoicegenerator/model/`
- create equivalent files under `src/main/java/br/com/itau/invoicegenerator/domain/model/`
- update imports in `src/main/java/**` and `src/test/java/**`

**Depends on:** none
**Reuses:** existing Lombok model classes and enum values.
**Requirements:** CLEAN-01.

**Done when:**
- [x] All model/enums compile from `domain.model`.
- [x] No code imports `br.com.itau.invoicegenerator.model`.
- [x] Enum names/values remain unchanged.

**Tests:** compile
**Gate:** `./mvnw test-compile`

**Commit:** `refactor(clean): move models into domain layer (T1)`

---

### T2: Add domain ports and preserve legacy rule services

**What:** Introduce port interfaces for tax, freight, stock, registration, delivery, and finance. Extract tax-rate selection to `TaxRateTable`; move per-item calculation and freight calculation into legacy-preserving domain services.

**Where:**
- create `src/main/java/br/com/itau/invoicegenerator/domain/port/*.java`
- create `src/main/java/br/com/itau/invoicegenerator/domain/service/TaxRateTable.java`
- create `src/main/java/br/com/itau/invoicegenerator/domain/service/LegacyProductTaxRateCalculator.java`
- create `src/main/java/br/com/itau/invoicegenerator/domain/service/LegacyFreightCalculator.java`
- delete old calculator/service equivalents after use-case migration

**Depends on:** T1
**Reuses:** business rules from `docs/business-rules.md`.
**Requirements:** CLEAN-10, CLEAN-15, CLEAN-16, CLEAN-17, CLEAN-22, CLEAN-23, CLEAN-24.

**Done when:**
- [x] Ports compile with domain types only.
- [x] `TaxRateTable` preserves all existing tax brackets and OUTROS/null fallthrough.
- [x] `LegacyProductTaxRateCalculator` preserves C-1 accumulation behavior.
- [x] `LegacyFreightCalculator` preserves region multipliers and the C-3 freight=0 fallback.

**Tests:** unit/characterization after T7.
**Gate:** `./mvnw test-compile`

**Commit:** `refactor(clean): extract domain ports and legacy rule services (T2)`

---

### T3: Verify domain/application dependency cleanliness

**What:** Confirm the new domain services compile and do not import Spring or Jackson. This task becomes the first guardrail before adding adapters.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/domain/**`
- later repeated for `application/**`

**Depends on:** T2
**Reuses:** grep import scan.
**Requirements:** CLEAN-04.

**Done when:**
- [x] Import scan over `domain` returns no Spring or Jackson matches.
- [x] `./mvnw test-compile` passes.

**Tests:** static scan + compile
**Gate:**

```bash
grep -R "org.springframework\|com.fasterxml.jackson" -n src/main/java/br/com/itau/invoicegenerator/domain
./mvnw test-compile
```

**Commit:** covered by T2/T4 implementation commit.

---

### T4: Extract `GenerateInvoiceUseCase` and `GenerateInvoiceInteractor`

**What:** Create the application boundary and move orchestration out of the legacy service implementation. The interactor receives all rule services and side-effect ports by constructor.

**Where:**
- create `src/main/java/br/com/itau/invoicegenerator/application/GenerateInvoiceUseCase.java`
- create `src/main/java/br/com/itau/invoicegenerator/application/GenerateInvoiceInteractor.java`
- delete old `service/InvoiceGeneratorService.java` and `service/impl/InvoiceGeneratorServiceImpl.java` after wiring is complete

**Depends on:** T2
**Reuses:** legacy orchestration order and invoice-building behavior.
**Requirements:** CLEAN-02, CLEAN-06, CLEAN-07, CLEAN-08, CLEAN-09, CLEAN-11.

**Done when:**
- [x] Controller/use-case callers depend on `GenerateInvoiceUseCase`.
- [x] Interactor has constructor-only dependencies.
- [x] Interactor builds invoices with UUID, `LocalDateTime.now()`, total items value, freight value, items, and recipient as before.
- [x] Interactor calls stock, registration, delivery, and accounts receivable ports in order.
- [x] No application code instantiates old side-effect services.

**Tests:** use-case orchestration test after T7.
**Gate:** `./mvnw test-compile`

**Commit:** `refactor(clean): extract invoice generation use case (T4)`

---

### T5: Move simulated integrations behind adapters

**What:** Replace old side-effect services with outbound adapters that implement domain ports. Preserve all sleeps and delivery slow-path behavior.

**Where:**
- create `adapter/integration/stock/StockIntegrationAdapter.java`
- create `adapter/integration/registration/InvoiceRegistrationAdapter.java`
- create `adapter/integration/finance/AccountsReceivableAdapter.java`
- create `adapter/integration/delivery/DeliveryIntegrationAdapter.java`
- create `adapter/integration/delivery/DeliverySchedulingClient.java`
- delete old `service/impl/{Stock,Registration,Delivery,Finance}Service.java`
- delete old `port/out/DeliveryIntegrationPort.java`

**Depends on:** T4
**Reuses:** sleep durations and side-effect method names from legacy classes.
**Requirements:** CLEAN-03, CLEAN-10, CLEAN-12, CLEAN-13.

**Done when:**
- [x] Each outbound adapter implements exactly one domain port, except delivery's internal client stub.
- [x] Sleep durations are preserved.
- [x] Delivery slow path still triggers for invoices with more than 5 items.

**Tests:** slow characterization and full verify.
**Gate:** `./mvnw test -Pslow`

**Commit:** `refactor(clean): move side effects behind integration adapters (T5)`

---

### T6: Move Spring wiring and HTTP controller to adapters

**What:** Add Spring composition in `adapter/config` and move the controller to `adapter/web`, depending on `GenerateInvoiceUseCase`.

**Where:**
- create `adapter/config/ApplicationBeanConfig.java`
- create/move `adapter/web/InvoiceController.java`
- delete old `web/controller/InvoiceController.java`

**Depends on:** T4, T5
**Reuses:** existing endpoint path `/api/orders/generate-invoice`.
**Requirements:** CLEAN-03, CLEAN-05, CLEAN-06, CLEAN-12.

**Done when:**
- [x] Spring context starts.
- [x] Controller injects `GenerateInvoiceUseCase`.
- [x] Endpoint path and HTTP method remain unchanged.
- [x] Spring annotations live only in adapter/config/web areas.

**Tests:** Spring context + MockMvc.
**Gate:** `./mvnw test -Dtest=InvoiceGeneratorApplicationTests,InvoiceControllerIntegrationTest`

**Commit:** `refactor(clean): wire use case through Spring adapters (T6)`

---

### T7: Add web DTOs and explicit DTO/domain mapper

**What:** Move JSON `@JsonProperty` annotations out of domain models into request/response DTOs owned by the web adapter. Add a mapper to translate between DTOs and domain models.

**Where:**
- create `src/main/java/br/com/itau/invoicegenerator/adapter/web/dto/*.java`
- update `adapter/web/InvoiceController.java`
- remove `@JsonProperty` imports/annotations from `domain/model/*.java`

**Depends on:** T6
**Reuses:** existing Portuguese snake_case keys from the old model classes.
**Requirements:** CLEAN-20, CLEAN-21, CLEAN-22, CLEAN-23, CLEAN-24.

**Done when:**
- [x] Domain models contain no Jackson imports.
- [x] Web DTOs preserve all request/response field names.
- [x] Nested DTO/domain mapping covers order, recipient, documents, addresses, items, invoice, and invoice items.
- [x] HTTP integration tests still pass with the two `paylods/` fixtures.

**Tests:** HTTP integration.
**Gate:**

```bash
./mvnw test -Dtest=InvoiceControllerIntegrationTest
grep -R "JsonProperty" -n src/main/java/br/com/itau/invoicegenerator/domain
```

**Commit:** `refactor(clean): isolate JSON mapping in web DTOs (T7)`

---

### T8: Migrate tests to the new use-case graph and remove Mockito usage

**What:** Update test imports/packages, rename the old orchestration test to `GenerateInvoiceInteractorTest`, add explicit fakes/builders for the new graph, and remove Mockito dependency usage.

**Where:**
- create `src/test/java/br/com/itau/invoicegenerator/GenerateInvoiceInteractorTest.java`
- create `src/test/java/br/com/itau/invoicegenerator/testsupport/RecordingTaxRateCalculator.java`
- create `src/test/java/br/com/itau/invoicegenerator/testsupport/TestUseCases.java`
- update all existing tests under `src/test/java/**`
- update `pom.xml` to exclude `mockito-core` and `mockito-junit-jupiter`
- delete old `InvoiceGeneratorServiceImplTest.java`
- rename calculator test to `LegacyProductTaxRateCalculatorTest.java`

**Depends on:** T4, T7
**Reuses:** existing safety-net assertions.
**Requirements:** CLEAN-09, CLEAN-28, CLEAN-29, CLEAN-30, CLEAN-31.

**Done when:**
- [x] No tests import Mockito or use `mock`, `when`, or `verify`.
- [x] Use-case tests use `RecordingTaxRateCalculator`.
- [x] Tax/freight/characterization tests build use cases through `TestUseCases`.
- [x] `./mvnw test` passes in the sandbox without escalated permissions.

**Tests:** full fast suite.
**Gate:**

```bash
grep -R "Mockito\|mock(\|verify(\|when(\|ArgumentMatchers" -n src/test/java
./mvnw test
```

**Commit:** `test(clean): migrate tests to use-case fakes (T8)`

---

### T9: Full verification

**What:** Run formatting and all gates after implementation stabilizes.

**Where:** whole repository.

**Depends on:** T8
**Reuses:** F-UPGRADE Maven gates.
**Requirements:** all CLEAN requirements.

**Done when:**
- [x] `./mvnw spotless:apply` succeeds.
- [x] `./mvnw verify` succeeds with 53 fast tests, packaging, Spotless, Checkstyle, and JaCoCo.
- [x] `./mvnw test -Pslow` succeeds with the slow delivery characterization.
- [x] Domain/application import scan returns no Spring/Jackson matches.

**Tests:** full fast + slow suites.
**Gate:**

```bash
./mvnw spotless:apply
./mvnw verify
./mvnw test -Pslow
grep -R "org.springframework\|com.fasterxml.jackson" -n \
  src/main/java/br/com/itau/invoicegenerator/domain \
  src/main/java/br/com/itau/invoicegenerator/application
```

**Commit:** `refactor(clean): verify clean architecture migration (T9)`

---

### T10: Update spec-driven documentation and project memory

**What:** Update feature docs, codebase docs, roadmap, state, and CLAUDE guidance so future tasks target the new architecture.

**Where:**
- `.specs/features/clean/spec.md`
- `.specs/features/clean/tasks.md`
- `.specs/codebase/ARCHITECTURE.md`
- `.specs/codebase/STRUCTURE.md`
- `.specs/codebase/CONVENTIONS.md`
- `.specs/codebase/INTEGRATIONS.md`
- `.specs/codebase/TESTING.md`
- `.specs/codebase/CONCERNS.md`
- `.specs/project/ROADMAP.md`
- `.specs/project/STATE.md`
- `CLAUDE.md`
- `docs/business-rules.md`

**Depends on:** T9
**Reuses:** implementation results and verification output.
**Requirements:** CLEAN-32, CLEAN-33, CLEAN-34, CLEAN-35.

**Done when:**
- [x] ROADMAP marks F-CLEAN complete and points next to F-DEFECTS-FUNCTIONAL.
- [x] STATE records AD-009 and quick task 012.
- [x] Codebase docs describe `domain/application/adapter` as current state.
- [x] F-CLEAN spec/tasks are detailed enough to audit after the fact.

**Tests:** documentation scan.
**Gate:**

```bash
grep -R "service.impl\|port/out\|web/controller" -n .specs/codebase CLAUDE.md
```

**Commit:** `docs(clean): document clean architecture refactor (T10)`

---

### T11: Refactor freight branch selection and document async guidance

**What:** Apply review feedback on the freight calculator: replace the long `if/else if` multiplier branch with a Java `switch`, and fix the delivery-region extraction so a matching address with `region=null` does not throw from `Stream.findFirst()`. Also document that the current code has no fire-and-forget; synchronous port calls remain until a durable outbox/queue design is implemented.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/domain/service/LegacyFreightCalculator.java`
- `src/test/java/br/com/itau/invoicegenerator/service/characterization/MissingRegionFreightCharacterizationTest.java`
- `.specs/features/clean/spec.md`
- `.specs/features/clean/tasks.md`
- `.specs/project/ROADMAP.md`
- `.specs/codebase/CONCERNS.md`
- `.specs/codebase/ARCHITECTURE.md`
- `docs/business-rules.md`
- `CLAUDE.md`

**Depends on:** T10
**Reuses:** existing freight multiplier and C-3 characterization tests.
**Requirements:** CLEAN-14, CLEAN-18, CLEAN-19, CLEAN-22, CLEAN-36.

**Done when:**
- [x] Freight multiplier selection uses a `switch (region)`.
- [x] Region lookup calls `findFirst()` on matching `Address` objects before mapping `Address::getRegion`.
- [x] Delivery address with `region=null` returned freight `0.0` instead of throwing at the end of F-CLEAN. F-DEFECTS-FUNCTIONAL later changed this to HTTP 400.
- [x] Docs/roadmap state that async/fire-and-forget is not present today and durable queues/outbox are the target for production async work.
- [x] Focused freight tests pass.

**Tests:** freight unit/characterization.
**Gate:**

```bash
./mvnw test -Dtest=FreightMultiplierTest,MissingRegionFreightCharacterizationTest
```

**Commit:** `refactor(clean): simplify freight calculation and null-safe region lookup (T11)`

---

### T12: Refactor tax-rate condition chains into switch and bracket tables

**What:** Apply the same readability cleanup to `TaxRateTable`: use `switch` for `PersonType` and `CompanyTaxRegime`, and move repeated threshold branches into reusable `TaxRateBracket` tables. At the end of F-CLEAN this preserved `OUTROS` and null tax-regime behavior as an empty rate; F-DEFECTS-FUNCTIONAL later changed those cases to typed HTTP 400 rejections.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/domain/service/TaxRateTable.java`
- `.specs/features/clean/spec.md`
- `.specs/features/clean/tasks.md`
- `.specs/project/ROADMAP.md`

**Depends on:** T11
**Reuses:** existing tax-rate selection tests and C-2 characterization tests.
**Requirements:** CLEAN-18, CLEAN-19, CLEAN-23.

**Done when:**
- [x] `PersonType` dispatch uses `switch`.
- [x] `CompanyTaxRegime` dispatch uses `switch`.
- [x] Repeated bracket condition logic is represented as `TaxRateBracket` data plus one lookup helper.
- [x] `OUTROS` and null tax regime still return no rate.
- [x] All tax-rate tests pass.

**Tests:** tax-rate unit/characterization.
**Gate:**

```bash
./mvnw test -Dtest=TaxRateSelectionFisicaTest,TaxRateSelectionSimplesNacionalTest,TaxRateSelectionLucroRealTest,TaxRateSelectionLucroPresumidoTest,UnhandledTaxRegimeCharacterizationTest
```

**Commit:** `refactor(clean): simplify tax-rate selection with switch and brackets (T12)`

---

## Requirement Traceability

| Requirement | Tasks | Verification |
| --- | --- | --- |
| CLEAN-01 | T1 | package/import compile |
| CLEAN-02 | T4 | `GenerateInvoiceUseCase` / interactor compile |
| CLEAN-03 | T5, T6, T7 | adapter package structure |
| CLEAN-04 | T3, T7, T9 | Spring/Jackson import scan |
| CLEAN-05 | T6 | Spring context test |
| CLEAN-06 | T4, T6 | controller depends on use case |
| CLEAN-07 | T4 | constructor dependencies |
| CLEAN-08 | T4 | fast suite characterization |
| CLEAN-09 | T4, T8 | `GenerateInvoiceInteractorTest` |
| CLEAN-10 | T2, T5 | domain ports + adapters |
| CLEAN-11 | T4 | no old side-effect `new` orchestration |
| CLEAN-12 | T5, T6 | context wiring |
| CLEAN-13 | T5, T9 | slow test |
| CLEAN-14 | T11 | side-effect scan/docs |
| CLEAN-15 | T2 | tax-rate tests |
| CLEAN-16 | T2 | calculator tests |
| CLEAN-17 | T2 | freight tests |
| CLEAN-18 | T12 | switch in tax-rate dispatch |
| CLEAN-19 | T12 | tax bracket tables |
| CLEAN-20 | T11 | switch in freight calculator |
| CLEAN-21 | T11 | null-safe region lookup |
| CLEAN-22 | T2, T8 | C-1 characterization |
| CLEAN-23 | T2, T8, T12 | C-2 characterization |
| CLEAN-24 | T2, T8, T11 | C-3 characterization |
| CLEAN-25 | T7 | MockMvc fixture tests |
| CLEAN-26 | T7 | response JSON assertions |
| CLEAN-27 | T7 | no-English-key assertions |
| CLEAN-28 | T7, T9 | domain Jackson scan |
| CLEAN-29 | T7 | DTO mapper |
| CLEAN-30 | T8 | Mockito grep |
| CLEAN-31 | T8 | `pom.xml` exclusions |
| CLEAN-32 | T8 | recording fake |
| CLEAN-33 | T8 | `TestUseCases` |
| CLEAN-34 | T10, T11, T12 | ROADMAP |
| CLEAN-35 | T10 | STATE |
| CLEAN-36 | T10, T11 | codebase docs / CLAUDE |
| CLEAN-37 | T10, T11, T12 | feature docs |
| CLEAN-38 | T11 | async guidance |

---

## Final Verification Log

- [x] `./mvnw spotless:apply` — success.
- [x] `./mvnw verify` — success; 53 fast tests, 0 failures/errors, Spotless clean, 0 Checkstyle violations, JaCoCo report generated.
- [x] `./mvnw test -Pslow` — success; 1 slow characterization test, 0 failures/errors.
- [x] `./mvnw test -Dtest=FreightMultiplierTest,MissingRegionFreightCharacterizationTest` — success; 8 tests, 0 failures/errors.
- [x] `./mvnw test -Dtest=TaxRateSelectionFisicaTest,TaxRateSelectionSimplesNacionalTest,TaxRateSelectionLucroRealTest,TaxRateSelectionLucroPresumidoTest,UnhandledTaxRegimeCharacterizationTest` — success; 36 tests, 0 failures/errors.
- [x] `grep -R "org.springframework\|com.fasterxml.jackson" -n src/main/java/br/com/itau/invoicegenerator/domain src/main/java/br/com/itau/invoicegenerator/application` — no matches.
- [x] `grep -R "Mockito\|mock(\|verify(\|when(\|ArgumentMatchers" -n src/test/java` — no matches.
