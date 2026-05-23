# F-CLEAN — Clean Architecture Refactor Specification

## Problem Statement

After F-SAFETY-NET and F-UPGRADE, the project had a modern Java/Spring baseline and a real characterization suite, but the invoice generation flow was still organized around legacy layers: `web/controller`, `service`, `service/impl`, `model`, and a partial `port/out` package. The orchestrator mixed tax-rate selection, per-item tax calculation, freight calculation, invoice assembly, and four blocking side effects in one service implementation.

That shape makes the next milestone risky. M2 needs to fix C-1, C-2, C-3, and C-4, but doing those fixes inside the old service would keep the same coupling that caused the defects to spread: framework concerns in the same path as business rules, side effects hidden behind `new XxxService()`, JSON annotations embedded in shared models, and tests forced to work around the application graph.

F-CLEAN's job is mostly a behavior-preserving refactor: introduce stable Clean Architecture boundaries before changing business behavior. Two narrow cleanups were added after review: replace the freight `if/else` ladder with a `switch` and make delivery-region extraction null-safe; then replace tax-rate `if/else` chains with `switch` dispatch and explicit bracket tables. The business policies for missing region and unsupported tax regime remain deferred to M2.

## Goals

- [x] Introduce explicit `domain/`, `application/`, and `adapter/` layers under `br.com.itau.invoicegenerator`.
- [x] Extract `GenerateInvoiceUseCase` as the only application entry point for invoice generation.
- [x] Move orchestration into `GenerateInvoiceInteractor`, with dependencies supplied through constructor-injected ports.
- [x] Define domain ports for every outbound dependency: tax calculation, freight calculation, stock, invoice registration, delivery, and accounts receivable.
- [x] Move simulated external systems into outbound adapters under `adapter/integration/**`.
- [x] Move HTTP and JSON transport concerns into `adapter/web/**`.
- [x] Keep Spring and Jackson imports out of `domain/` and `application/`.
- [x] Preserve the Portuguese snake_case JSON contract.
- [x] Preserve C-1, C-2, C-4, and C-6 behavior exactly enough for the existing tests to keep passing.
- [x] Refactor freight calculation from `if/else` ladder to `switch`.
- [x] Make delivery-region selection null-safe; `region=null` now follows the same freight `0.0` fallback as no delivery address.
- [x] Refactor tax-rate selection from nested `if/else` chains to `switch` dispatch and reusable bracket tables.
- [x] Document that there is no fire-and-forget implementation today; side effects are synchronous and durable async belongs to F-DEFECTS-PERFORMANCE / F-RESILIENCE.
- [x] Update spec-driven documentation to describe the new architecture as current state.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Fixing C-1 accumulation | Belongs in M2 / F-DEFECTS-FUNCTIONAL. F-CLEAN preserves it through `LegacyProductTaxRateCalculator`. |
| Defining behavior for `taxRegime=OUTROS` or null | Belongs in M2; F-CLEAN keeps the empty-items fallthrough. |
| Defining the final missing-region policy | Belongs in M2; F-CLEAN only removes the null-region NPE and keeps the current freight `0.0` fallback. |
| Migrating monetary fields from `double` to `BigDecimal` | Belongs in M2 / C-4. |
| Making side effects async or resilient | Belongs in F-DEFECTS-PERFORMANCE and F-RESILIENCE. Sleeps are preserved. |
| Adding persistence/outbox | No persistence exists yet; outbox belongs with async/resilience work. |
| Changing endpoint paths, request fields, response fields, or enum values | Explicit challenge constraint; JSON contract is frozen. |
| Adding validation / `@ControllerAdvice` error model | Useful, but behavior-changing. Deferred to defect closure / resilience work. |

---

## User Stories

### P1: Clean layer split with enforceable dependencies ⭐ MVP

**User Story**: As the developer preparing to fix defects, I want the code split into domain, application, and adapters, so that business logic can evolve without being coupled to Spring MVC, Jackson, or integration stubs.

**Why P1**: This is the core of F-CLEAN. Without the package and dependency split, later defect fixes still happen in the same legacy service shape.

**Acceptance Criteria**:

1. **WHEN** production source files are inspected **THEN** domain models, ports, and business-rule services SHALL live under `src/main/java/br/com/itau/invoicegenerator/domain`.
2. **WHEN** production source files are inspected **THEN** use case contracts and interactors SHALL live under `src/main/java/br/com/itau/invoicegenerator/application`.
3. **WHEN** production source files are inspected **THEN** Spring MVC, JSON DTOs, Spring configuration, and simulated integration implementations SHALL live under `src/main/java/br/com/itau/invoicegenerator/adapter`.
4. **WHEN** `grep -R "org.springframework\|com.fasterxml.jackson"` runs over `domain` and `application` **THEN** it SHALL return no matches.
5. **WHEN** the application starts **THEN** Spring SHALL compose the use case from adapter/config beans without adding Spring annotations to domain/application classes.

**Independent Test**:

```bash
grep -R "org.springframework\|com.fasterxml.jackson" -n \
  src/main/java/br/com/itau/invoicegenerator/domain \
  src/main/java/br/com/itau/invoicegenerator/application
./mvnw test -Dtest=InvoiceGeneratorApplicationTests
```

---

### P1: Application use case extraction ⭐ MVP

**User Story**: As a caller of invoice generation inside the codebase, I want a single `GenerateInvoiceUseCase` boundary, so that HTTP is only one adapter into the application and future callers do not depend on web/controller classes.

**Why P1**: The use case boundary is what lets M2 change behavior in one place while adapters remain thin.

**Acceptance Criteria**:

6. **WHEN** invoice generation is invoked from the HTTP controller **THEN** the controller SHALL depend on `GenerateInvoiceUseCase`, not on a legacy service implementation.
7. **WHEN** `GenerateInvoiceInteractor` is constructed **THEN** all collaborators SHALL be supplied through its constructor.
8. **WHEN** `GenerateInvoiceInteractor.generateInvoice(order)` runs **THEN** it SHALL still perform the legacy sequence: select rate, calculate invoice items when a rate exists, calculate freight, build invoice, call stock, registration, delivery, and finance ports.
9. **WHEN** a test needs use-case orchestration without Spring **THEN** it SHALL be able to build the interactor directly via test support.

**Independent Test**:

```bash
./mvnw test -Dtest=GenerateInvoiceInteractorTest
```

---

### P1: Domain ports for side effects ⭐ MVP

**User Story**: As the developer preparing async/resilience work, I want every external side effect behind a domain port, so that adapters can later be made async, retried, timed out, or replaced without rewriting business orchestration.

**Why P1**: The old implementation hid side effects behind `new StockService()`, `new RegistrationService()`, `new DeliveryService()`, and `new FinanceService()` inside the orchestrator. That blocks testability and resilience.

**Acceptance Criteria**:

10. **WHEN** production code is inspected **THEN** `domain/port` SHALL define `StockPort`, `InvoiceRegistrationPort`, `DeliveryPort`, and `AccountsReceivablePort`.
11. **WHEN** production code is inspected **THEN** no application use-case code SHALL instantiate old side-effect services with `new`.
12. **WHEN** the app context starts **THEN** Spring SHALL wire concrete adapters from `adapter/integration/**` into the interactor.
13. **WHEN** side effects execute **THEN** their legacy sleep budgets SHALL be preserved: stock 380 ms, registration 500 ms, delivery adapter 150 ms + client 200 ms and +5000 ms for invoices with more than 5 items, finance 250 ms.
14. **WHEN** side-effect code is inspected **THEN** there SHALL be no fire-and-forget mechanism in the current implementation; calls are synchronous. Future async work SHALL prefer durable queue/outbox over detached threads.

**Independent Test**:

```bash
./mvnw test -Pslow
./mvnw verify
```

---

### P1: Tax and freight extraction without defect fixes ⭐ MVP

**User Story**: As the developer fixing tax and freight defects next, I want tax-rate selection, per-item tax math, and freight calculation separated from orchestration, so that M2 can change each rule in the smallest possible place.

**Why P1**: C-1, C-2, and C-3 are all rule-level problems. Extracting them first makes later fixes smaller and easier to review.

**Acceptance Criteria**:

15. **WHEN** tax-rate selection is inspected **THEN** it SHALL live in `TaxRateTable`.
16. **WHEN** per-item tax math is inspected **THEN** it SHALL live behind `TaxRateCalculator`.
17. **WHEN** freight calculation is inspected **THEN** it SHALL live behind `FreightCalculator`.
18. **WHEN** tax-rate selection is inspected **THEN** `PersonType` and `CompanyTaxRegime` dispatch SHALL use `switch`, not nested `if/else`.
19. **WHEN** tax brackets are inspected **THEN** repeated threshold logic SHALL live in reusable bracket tables preserving the exact documented boundaries.
20. **WHEN** freight multipliers are inspected **THEN** region dispatch SHALL use a `switch`, not a chain of `if/else if`.
21. **WHEN** delivery-region selection is inspected **THEN** it SHALL call `findFirst()` on matching addresses before mapping `Address::getRegion`, so null region values do not cause `Stream.findFirst()` NPE.
22. **WHEN** C-1 characterization runs **THEN** the singleton calculator accumulation SHALL still be observable.
23. **WHEN** C-2 characterization runs at the end of F-CLEAN **THEN** JURIDICA + `OUTROS`/null SHALL still produce an invoice with an empty `items` list. F-DEFECTS-FUNCTIONAL later flips this to typed HTTP 400.
24. **WHEN** C-3 characterization runs **THEN** no delivery address and delivery address with null region SHALL both produce freight `0.0`; final reject-vs-pass-through policy remains deferred to M2.

**Independent Test**:

```bash
./mvnw test -Dtest='*TaxRateSelection*Test,FreightMultiplierTest,*CharacterizationTest'
```

---

### P1: JSON contract isolated in web DTOs ⭐ MVP

**User Story**: As an API consumer, I want the exact same Portuguese snake_case request/response contract after the refactor, while the internal domain model stays free of JSON annotations.

**Why P1**: The challenge explicitly forbids changing payloads. Moving Jackson annotations out of domain is only acceptable if the HTTP integration tests prove the wire contract stayed intact.

**Acceptance Criteria**:

25. **WHEN** request JSON uses fields like `id_pedido`, `valor_total_itens`, `tipo_pessoa`, and `regime_tributacao` **THEN** the endpoint SHALL still deserialize successfully.
26. **WHEN** the endpoint responds **THEN** response JSON SHALL use fields like `id_nota_fiscal`, `valor_frete`, `valor_tributo_item`, and `destinatario`.
27. **WHEN** response JSON is inspected **THEN** English internal field names SHALL NOT appear.
28. **WHEN** domain model classes are inspected **THEN** they SHALL NOT contain `@JsonProperty`.
29. **WHEN** web DTO classes are inspected **THEN** they SHALL own the `@JsonProperty` mapping and explicit DTO/domain mapping.

**Independent Test**:

```bash
./mvnw test -Dtest=InvoiceControllerIntegrationTest
grep -R "JsonProperty" -n src/main/java/br/com/itau/invoicegenerator/domain
```

---

### P2: Test cleanup after architecture split

**User Story**: As the developer maintaining the suite, I want tests to use explicit fakes and test builders instead of framework mocks, so that restricted JVM environments can run the suite reliably and the tests reflect the new use-case boundary.

**Why P2**: Mockito was no longer needed after the interactor became directly constructible. Removing it also avoids ByteBuddy self-attach issues in sandboxed runs.

**Acceptance Criteria**:

30. **WHEN** tests are inspected **THEN** no test SHALL import Mockito or use `@Mock`, `when`, or `verify`.
31. **WHEN** the Maven test starter is inspected **THEN** Mockito artifacts SHALL be excluded from `spring-boot-starter-test`.
32. **WHEN** orchestration tests need a tax calculator double **THEN** they SHALL use `RecordingTaxRateCalculator`.
33. **WHEN** tests need a full use-case graph **THEN** they SHALL use `TestUseCases`.

**Independent Test**:

```bash
grep -R "Mockito\|mock(\|verify(\|when(\|ArgumentMatchers" -n src/test/java
./mvnw test
```

---

### P2: Spec-driven documentation update

**User Story**: As the next contributor, I want ROADMAP, STATE, and codebase docs to describe the post-F-CLEAN architecture, so that future tasks do not accidentally follow the removed service/impl structure.

**Why P2**: In spec-driven development, stale docs become bad instructions. This feature changed the central architecture, so docs are part of the deliverable.

**Acceptance Criteria**:

34. **WHEN** `.specs/project/ROADMAP.md` is read **THEN** F-CLEAN SHALL be marked complete with verification notes.
35. **WHEN** `.specs/project/STATE.md` is read **THEN** it SHALL record the Clean Architecture decision and next work item.
36. **WHEN** `.specs/codebase/*.md` and `CLAUDE.md` are read **THEN** they SHALL describe `domain/application/adapter` as current state, not the old `service/impl` layout.
37. **WHEN** `.specs/features/clean/spec.md` and `tasks.md` are read **THEN** they SHALL provide enough detail to understand the work after the fact.
38. **WHEN** the roadmap is read **THEN** it SHALL separate today's synchronous side-effect calls from future durable async/outbox work and avoid recommending detached fire-and-forget threads as the production solution.

**Independent Test**:

```bash
grep -R "service.impl\|port/out\|web/controller" -n .specs/codebase CLAUDE.md
```

---

## Edge Cases

- **WHEN** an order maps from JSON DTO to domain and contains nested recipient, documents, addresses, and items **THEN** all nested fields SHALL survive the round trip into the use case.
- **WHEN** optional nested lists are null **THEN** the DTO mapper SHALL preserve null rather than inventing empty lists.
- **WHEN** `TaxRateTable` returns no rate **THEN** the interactor SHALL preserve the legacy empty invoice-items behavior.
- **WHEN** a delivery address exists with `region=null` **THEN** freight SHALL fall back to `0.0` without throwing.
- **WHEN** no delivery address exists **THEN** freight SHALL still fall back to `0.0` until M2 defines the final policy.
- **WHEN** the same singleton `LegacyProductTaxRateCalculator` is used twice **THEN** the second result SHALL still include accumulated items until C-1 is fixed.
- **WHEN** the delivery integration sees more than 5 invoice items **THEN** the slow path SHALL still be exercised by the slow characterization test.
- **WHEN** a future adapter is added **THEN** it should implement a `domain/port` interface and be wired from `adapter/config`, not referenced directly from the use case.

## Requirement Traceability

| Requirement ID | Story | Status |
| --- | --- | --- |
| CLEAN-01 | P1 — Domain package introduced | Verified |
| CLEAN-02 | P1 — Application package introduced | Verified |
| CLEAN-03 | P1 — Adapter package introduced | Verified |
| CLEAN-04 | P1 — No Spring/Jackson in domain/application | Verified |
| CLEAN-05 | P1 — Spring composition from adapter/config | Verified |
| CLEAN-06 | P1 — Controller depends on use case | Verified |
| CLEAN-07 | P1 — Interactor constructor dependencies | Verified |
| CLEAN-08 | P1 — Legacy orchestration sequence preserved | Verified |
| CLEAN-09 | P1 — Direct no-Spring use-case tests | Verified |
| CLEAN-10 | P1 — Side-effect ports defined | Verified |
| CLEAN-11 | P1 — No old `new XxxService()` orchestration | Verified |
| CLEAN-12 | P1 — Adapter implementations wired | Verified |
| CLEAN-13 | P1 — Legacy sleep budgets preserved | Verified |
| CLEAN-14 | P1 — No current fire-and-forget implementation | Verified |
| CLEAN-15 | P1 — Tax-rate table extracted | Verified |
| CLEAN-16 | P1 — Tax calculator port extracted | Verified |
| CLEAN-17 | P1 — Freight calculator port extracted | Verified |
| CLEAN-18 | P1 — Tax-rate dispatch uses switch | Verified |
| CLEAN-19 | P1 — Tax brackets use reusable tables | Verified |
| CLEAN-20 | P1 — Freight multiplier uses switch | Verified |
| CLEAN-21 | P1 — Delivery-region selection null-safe | Verified |
| CLEAN-22 | P1 — C-1 behavior preserved | Verified |
| CLEAN-23 | P1 — C-2 behavior preserved | Verified |
| CLEAN-24 | P1 — C-3 freight=0 fallback preserved for missing/null region | Verified |
| CLEAN-25 | P1 — Request JSON contract preserved | Verified |
| CLEAN-26 | P1 — Response JSON contract preserved | Verified |
| CLEAN-27 | P1 — No English keys in response | Verified |
| CLEAN-28 | P1 — Domain models have no `@JsonProperty` | Verified |
| CLEAN-29 | P1 — Web DTO mapper owns JSON mapping | Verified |
| CLEAN-30 | P2 — Mockito removed from tests | Verified |
| CLEAN-31 | P2 — Mockito excluded from test starter | Verified |
| CLEAN-32 | P2 — Recording fake used for orchestration | Verified |
| CLEAN-33 | P2 — Test use-case factory available | Verified |
| CLEAN-34 | P2 — ROADMAP updated | Verified |
| CLEAN-35 | P2 — STATE updated | Verified |
| CLEAN-36 | P2 — Codebase docs / CLAUDE updated | Verified |
| CLEAN-37 | P2 — Feature spec/tasks expanded | Verified |
| CLEAN-38 | P2 — Async guidance recorded in roadmap | Verified |

**ID format:** `CLEAN-NN`

**Status values:** Pending -> In Design -> In Tasks -> Implementing -> Verified

## Success Criteria

- [x] `./mvnw verify` passes with 53 fast tests, Spotless, Checkstyle, packaging, and JaCoCo.
- [x] `./mvnw test -Pslow` passes with the slow delivery characterization.
- [x] Domain and application import scan returns no Spring or Jackson imports.
- [x] HTTP integration tests preserve Portuguese snake_case JSON.
- [x] No Mockito usage remains in tests.
- [x] Freight calculation uses `switch` and null-safe delivery-region selection.
- [x] Tax-rate selection uses `switch` and reusable bracket tables.
- [x] M2 can now start from explicit rule classes and ports instead of the old monolithic service.
