# F-SAFETY-NET — Real Test Suite Specification

## Problem Statement

The project has two existing test classes and both are flawed: `InvoiceGeneratorServiceImplTest` declares `@Mock` for a collaborator that the SUT never actually injects, and `InvoiceGeneratorApplicationTests` only verifies that Spring boots. Combined with the static-list defect in `ProductTaxRateCalculator`, the existing suite is also order-dependent (the second test fails if the first runs in the same JVM). There is no test coverage for: the JURIDICA × tax-regime branches, freight multipliers, bracket edges, the OUTROS / null fallthrough, the missing-region freight=0 path, the >5-items 5-second sleep, or the HTTP layer.

Before refactoring (F-UPGRADE, F-CLEAN) or fixing defects (M2), we need a test suite that *captures the current behavior* — including the known bugs as characterization tests — so any subsequent change either keeps the contract or explicitly flips a previously-red test to green with a documented decision.

## Goals

- [ ] Test coverage for every documented business rule in `docs/business-rules.md` §3 (tax) and §4 (freight), including bracket-edge cases.
- [ ] Characterization tests pinning the current behavior of every defect in `docs/business-rules.md` §6 (C-1 to C-4), so M2 has explicit red→green flips with no surprises.
- [ ] End-to-end coverage of `POST /api/orders/generate-invoice` exercised through `MockMvc` with the two `payloads/` fixtures.
- [ ] Zero order-dependent tests. Running `./mvnw test` repeatedly produces identical results.
- [ ] Calculator made constructor-injectable so the existing misleading `@Mock` becomes either correct (mock actually used) or removed.
- [ ] JaCoCo wired and producing a report on `./mvnw verify` (measurement only — no gate enforcement yet; gate lives in F-UPGRADE).

## Out of Scope

| Feature                                                      | Reason                                                                                                     |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| Fixing C-1 (static-list accumulation)                        | Belongs in M2 / F-DEFECTS-FUNCTIONAL. Here we only **characterize** the bug.                                |
| Fixing C-2 (OUTROS/null tax-regime fallthrough)              | Same — characterize only.                                                                                  |
| Fixing C-3 (missing-region freight=0)                        | Same — characterize only.                                                                                  |
| Migrating to `BigDecimal` (C-4)                              | Belongs in M2. Tests must assert against `double` values exactly as the code returns them today.            |
| Performance tests (latency, throughput)                      | Belongs in M3 / F-DEFECTS-PERFORMANCE.                                                                     |
| Authentication / authorization tests                         | Out of scope of v1 implementation (only documented in F-AWS).                                              |
| Coverage **gate** in CI (enforced thresholds)                | Belongs in F-UPGRADE alongside the toolchain refresh. Here we only **measure**.                            |
| Clean Architecture restructuring (use cases + adapters)      | F-CLEAN. Constructor-injection here is the minimum to enable proper mocking, not a layer split.            |
| Mutation testing                                             | Nice-to-have; deferred.                                                                                    |

---

## User Stories

### P1: Bracket-edge coverage for tax calculation ⭐ MVP

**User Story**: As the developer about to refactor this service, I want every documented tax bracket and edge to be guarded by a passing test, so that the refactor cannot silently change tax math without a failing test.

**Why P1**: The refactor is risky precisely *because* there's no safety net. Without this story, F-CLEAN is gambling. Tax math is the core domain.

**Acceptance Criteria**:

1. **WHEN** a test runs `generateInvoice` with `personType=FISICA` and `totalItemsValue ∈ {0, 499.99}` **THEN** the system SHALL produce invoice items where `itemTaxValue = unitPrice * 0` (rate 0%).
2. **WHEN** `personType=FISICA` and `totalItemsValue = 500` **THEN** the system SHALL apply rate 0.12 (boundary belongs to the middle bracket per `business-rules.md` §3.5).
3. **WHEN** `personType=FISICA` and `totalItemsValue ∈ {500, 1000, 2000}` **THEN** the system SHALL apply rate 0.12.
4. **WHEN** `personType=FISICA` and `totalItemsValue ∈ {2000.01, 3000, 3500}` **THEN** the system SHALL apply rate 0.15.
5. **WHEN** `personType=FISICA` and `totalItemsValue ∈ {3500.01, 10000}` **THEN** the system SHALL apply rate 0.17.
6. **WHEN** `personType=JURIDICA` and `taxRegime=SIMPLES_NACIONAL` and `totalItemsValue ∈ {0, 999.99}` **THEN** the system SHALL apply rate 0.03.
7. **WHEN** `personType=JURIDICA` and `taxRegime=SIMPLES_NACIONAL` and `totalItemsValue ∈ {1000, 2000}` **THEN** the system SHALL apply rate 0.07.
8. **WHEN** `personType=JURIDICA` and `taxRegime=SIMPLES_NACIONAL` and `totalItemsValue ∈ {2000.01, 5000}` **THEN** the system SHALL apply rate 0.13.
9. **WHEN** `personType=JURIDICA` and `taxRegime=SIMPLES_NACIONAL` and `totalItemsValue ∈ {5000.01, 10000}` **THEN** the system SHALL apply rate 0.19.
10. **WHEN** `personType=JURIDICA` and `taxRegime=LUCRO_REAL` and `totalItemsValue` hits each of the 4 brackets **THEN** the system SHALL apply rates {0.03, 0.09, 0.15, 0.20} respectively.
11. **WHEN** `personType=JURIDICA` and `taxRegime=LUCRO_PRESUMIDO` and `totalItemsValue` hits each of the 4 brackets **THEN** the system SHALL apply rates {0.03, 0.09, 0.16, 0.20} respectively.

**Independent Test**: Run `./mvnw test -Dtest='*TaxRateTest'` (new test class) and observe ≥16 passing cases covering every bracket × person-type × regime combination.

---

### P1: Freight multiplier coverage per region ⭐ MVP

**User Story**: As the developer about to refactor, I want every region multiplier and the missing-region fallthrough to be pinned by tests, so that touching the freight code or extracting a `FreightCalculator` strategy cannot silently change the freight a customer is charged.

**Why P1**: Freight is the second of two value paths customers see on the invoice. Same risk profile as tax math.

**Acceptance Criteria**:

12. **WHEN** the recipient has an address with `purpose=ENTREGA` and `region=NORTE` and `freightValue=100` **THEN** the system SHALL set `invoice.freightValue = 108.0`.
13. **WHEN** `region=NORDESTE` and `freightValue=100` **THEN** `invoice.freightValue = 108.5`.
14. **WHEN** `region=CENTRO_OESTE` and `freightValue=100` **THEN** `invoice.freightValue = 107.0`.
15. **WHEN** `region=SUDESTE` and `freightValue=100` **THEN** `invoice.freightValue = 104.8`.
16. **WHEN** `region=SUL` and `freightValue=100` **THEN** `invoice.freightValue = 106.0`.
17. **WHEN** `purpose=COBRANCA_ENTREGA` (instead of `ENTREGA`) with `region=SUL` **THEN** the system SHALL still apply the multiplier (the lookup matches both purposes).
18. **WHEN** the recipient has only addresses with `purpose=COBRANCA` (no delivery) **THEN** the system SHALL set `invoice.freightValue = 0.0` — *characterization of bug C-3; will be flipped in M2*.
19. **WHEN** the recipient has an address with `purpose=ENTREGA` but `region=null` **THEN** the system SHALL return freight `0.0` after F-CLEAN T11. Historical note: F-SAFETY-NET originally discovered a `Stream.findFirst()` NPE here; that accidental exception path was removed without deciding the final M2 policy.

**Independent Test**: Run `./mvnw test -Dtest='*FreightTest'` and observe ≥7 passing cases.

---

### P1: Characterization tests for known defects ⭐ MVP

**User Story**: As the developer about to fix defects in M2, I want each known defect pinned by a failing-on-fix test, so that the M2 work is concretely "make this red test green".

**Why P1**: Without characterizing the bugs, M2 fixes are unobservable — there's no "before" to compare against.

**Acceptance Criteria**:

20. **WHEN** two `generateInvoice` calls run in sequence within the same JVM and each receives 1 item **THEN** the second call SHALL return an invoice with `items.size() = 2` — *characterization of C-1; M2 flips to assert `= 1`*.
21. **WHEN** `personType=JURIDICA` and `taxRegime=OUTROS` (declared enum constant) **THEN** the system SHALL return an invoice with `items.size() = 0` — *characterization of C-2*.
22. **WHEN** `personType=JURIDICA` and `taxRegime=null` **THEN** the system SHALL return an invoice with `items.size() = 0` — *characterization of C-2*.
23. **WHEN** an order has `items.size() = 6` (above the 5-item threshold) **THEN** the system SHALL succeed but `generateInvoice` SHALL take ≥ 5000 ms wall-clock — *characterization of C-6's >5-items 5s sleep; test tagged `@Tag("slow")` and excluded from the default profile*.

**Independent Test**: Run `./mvnw test -Dtest='*CharacterizationTest'`. Each test passes today; after M2 the same test class will have explicit `// FLIPPED IN M2` markers indicating which expectations were inverted.

---

### P1: End-to-end HTTP coverage with sample payloads ⭐ MVP

**User Story**: As the reviewer evaluating the challenge, I want a single test that exercises `POST /api/orders/generate-invoice` from JSON in to JSON out using the existing sample payloads, so that the JSON contract is mechanically verified and the rename's preservation of the contract is provable.

**Why P1**: It is the only test that actually proves the JSON contract is intact end-to-end. Without it, F-CLEAN's adapter restructuring could subtly break field mapping.

**Acceptance Criteria**:

24. **WHEN** `POST /api/orders/generate-invoice` is called with the body of `src/main/resources/payloads/teste-pf.json` **THEN** the system SHALL respond with HTTP 200, `Content-Type: application/json`, and a body containing the keys `id_nota_fiscal`, `data`, `valor_total_itens=100.0`, `valor_frete=10.48` (10 × 1.048 for SUDESTE), `itens[0].valor_tributo_item=0.0` (FISICA < 500 → 0% rate), and `destinatario.tipo_pessoa="FISICA"`.
25. **WHEN** `POST /api/orders/generate-invoice` is called with the body of `src/main/resources/payloads/teste-pj-simples.json` **THEN** the system SHALL respond with HTTP 200 and a body containing `valor_frete=75.456` (72 × 1.048 SUDESTE), `itens[0].valor_tributo_item=138.7` (730 × 0.19, since `totalItemsValue=5840 > 5000` → SIMPLES_NACIONAL rate 0.19), and `destinatario.tipo_pessoa="JURIDICA"`. F-DEFECTS-FUNCTIONAL later changes this expected freight to `75.46` because calculated money is rounded to scale 2.
26. **WHEN** the request body uses snake_case Portuguese keys **THEN** the response body SHALL also use snake_case Portuguese keys — no English keys SHALL appear in the JSON.

**Independent Test**: Run `./mvnw test -Dtest=InvoiceControllerIntegrationTest` (new). Test uses `MockMvc` against the real Spring context.

---

### P1: Calculator made constructor-injectable ⭐ MVP

**User Story**: As the developer writing the new tests, I want `ProductTaxRateCalculator` injected into `InvoiceGeneratorServiceImpl` via the constructor, so that tests can isolate the service from the calculator (and vice versa), and the misleading `@Mock` in `InvoiceGeneratorServiceImplTest` (C-5) becomes either correct or unnecessary.

**Why P1**: Without this, every test of the service is forced to run the real calculator (and inherit its static-list bug). Mocks don't work; isolation is impossible.

**Acceptance Criteria**:

27. **WHEN** `InvoiceGeneratorServiceImpl` is constructed **THEN** it SHALL accept a `ProductTaxRateCalculator` instance as a constructor parameter.
28. **WHEN** the Spring context starts **THEN** `ProductTaxRateCalculator` SHALL be available as a bean (`@Component` or equivalent) and SHALL be injected into the service.
29. **WHEN** `InvoiceGeneratorServiceImplTest` declares `@Mock ProductTaxRateCalculator` and stubs `calculateTax(any(), any())` **THEN** the stub SHALL be observed by the SUT (i.e., the mock is actually used).

**Independent Test**: A new unit test that stubs `calculateTax` to return a hand-built `List<InvoiceItem>` and asserts the service returns *exactly* that list — proves the mock is wired.

---

### P2: JaCoCo coverage report (measurement only, no gate)

**User Story**: As the developer / reviewer, I want a coverage report generated on `./mvnw verify`, so that we can see numbers without yet enforcing them.

**Why P2**: Useful for visibility but not blocking the M1 → M2 transition. The enforced gate is intentionally deferred to F-UPGRADE.

**Acceptance Criteria**:

30. **WHEN** `./mvnw verify` runs **THEN** a JaCoCo HTML report SHALL be produced at `target/site/jacoco/index.html`.
31. **WHEN** the report is opened **THEN** it SHALL show line and branch coverage broken down by package.

**Independent Test**: `./mvnw verify && open target/site/jacoco/index.html`.

---

### P3: Test data builders for `Order`, `Recipient`, `Item`

**User Story**: As the developer writing all of P1's tests, I want one-line builders for the common DTOs, so that the tests stay focused on what they're asserting and don't drown in setup boilerplate.

**Why P3**: Reduces repetition; not strictly required for the safety net to function.

**Acceptance Criteria**:

32. **WHEN** a test needs an `Order` with `personType=FISICA` and `totalItemsValue=400` **THEN** the test SHALL be able to construct it in one line via `Orders.fisica(400)` (or similar).

---

## Edge Cases

- **WHEN** an order has zero items (`items = []`) **THEN** the system SHALL return an invoice with `items = []` and `itemTaxValue` of zero collectively. *(Characterization — current behavior of the loop.)*
- **WHEN** an item has `unitPrice = 0` **THEN** `itemTaxValue` SHALL be `0` regardless of the bracket. *(Tax-rate × 0 = 0.)*
- **WHEN** an item has `quantity = 0` **THEN** the calculation SHALL still produce an `InvoiceItem` (because the current code does not consult `quantity` in the tax formula — see `business-rules.md` §3).
- **WHEN** the recipient has multiple addresses with `purpose ∈ {ENTREGA, COBRANCA_ENTREGA}` and different regions **THEN** the system SHALL use the **first** matching address's region. *(`findFirst()` semantics.)*
- **WHEN** the JSON request omits `regime_tributacao` for a `JURIDICA` recipient **THEN** the system SHALL treat the field as `null` and trigger the OUTROS/null fallthrough characterized in `SAFETY-22`.
- **WHEN** the JSON request includes an unknown enum value (e.g., `tipo_pessoa: "FOO"`) **THEN** Jackson SHALL fail deserialization with HTTP 400 / 500 — *current behavior is implicit; characterize what the framework does today.*

---

## Requirement Traceability

| Requirement ID | Story                                | Phase   | Status  |
| -------------- | ------------------------------------ | ------- | ------- |
| SAFETY-01      | P1 — Bracket-edge coverage (FISICA 0%)            | Pending | Pending |
| SAFETY-02      | P1 — Bracket-edge coverage (FISICA 12% lower edge) | Pending | Pending |
| SAFETY-03      | P1 — Bracket-edge coverage (FISICA 12%)            | Pending | Pending |
| SAFETY-04      | P1 — Bracket-edge coverage (FISICA 15%)            | Pending | Pending |
| SAFETY-05      | P1 — Bracket-edge coverage (FISICA 17%)            | Pending | Pending |
| SAFETY-06      | P1 — Bracket-edge coverage (SIMPLES 3%)            | Pending | Pending |
| SAFETY-07      | P1 — Bracket-edge coverage (SIMPLES 7%)            | Pending | Pending |
| SAFETY-08      | P1 — Bracket-edge coverage (SIMPLES 13%)           | Pending | Pending |
| SAFETY-09      | P1 — Bracket-edge coverage (SIMPLES 19%)           | Pending | Pending |
| SAFETY-10      | P1 — Bracket-edge coverage (LUCRO_REAL all 4)      | Pending | Pending |
| SAFETY-11      | P1 — Bracket-edge coverage (LUCRO_PRESUMIDO all 4) | Pending | Pending |
| SAFETY-12      | P1 — Freight × NORTE                                | Pending | Pending |
| SAFETY-13      | P1 — Freight × NORDESTE                             | Pending | Pending |
| SAFETY-14      | P1 — Freight × CENTRO_OESTE                         | Pending | Pending |
| SAFETY-15      | P1 — Freight × SUDESTE                              | Pending | Pending |
| SAFETY-16      | P1 — Freight × SUL                                  | Pending | Pending |
| SAFETY-17      | P1 — Freight × COBRANCA_ENTREGA purpose             | Pending | Pending |
| SAFETY-18      | P1 — Characterize: no delivery → freight = 0        | Pending | Pending |
| SAFETY-19      | P1 — Characterize: null region → freight = 0        | Verified after F-CLEAN T11 | Verified |
| SAFETY-20      | P1 — Characterize: static-list accumulates (C-1)    | Pending | Pending |
| SAFETY-21      | P1 — Characterize: OUTROS → items=[]                | Pending | Pending |
| SAFETY-22      | P1 — Characterize: null taxRegime → items=[]        | Pending | Pending |
| SAFETY-23      | P1 — Characterize: >5 items wall-clock ≥ 5s (slow)  | Pending | Pending |
| SAFETY-24      | P1 — E2E: teste-pf.json HTTP→JSON                   | Pending | Pending |
| SAFETY-25      | P1 — E2E: teste-pj-simples.json HTTP→JSON           | Pending | Pending |
| SAFETY-26      | P1 — E2E: response uses snake_case Portuguese keys  | Pending | Pending |
| SAFETY-27      | P1 — Constructor-injection for calculator           | Pending | Pending |
| SAFETY-28      | P1 — Calculator registered as Spring bean           | Pending | Pending |
| SAFETY-29      | P1 — Mock of calculator is actually observed by SUT | Pending | Pending |
| SAFETY-30      | P2 — JaCoCo HTML report on `verify`                 | Pending | Pending |
| SAFETY-31      | P2 — Coverage broken down by package                | Pending | Pending |
| SAFETY-32      | P3 — Test data builders                             | Pending | Pending |

**ID format:** `SAFETY-NN`

**Status values:** Pending → In Design → In Tasks → Implementing → Verified

**Coverage:** 32 total, 0 mapped to tasks, 32 unmapped ⚠️ (will be mapped during the Tasks phase)

---

## Success Criteria

- [ ] `./mvnw test` runs ≥ 25 tests, all green, and produces identical results on three consecutive runs (no order dependence).
- [ ] The four documented defects (C-1, C-2, C-3, C-6) each have at least one explicit characterization test referenced by ID in the test source.
- [ ] `./mvnw verify` produces a JaCoCo HTML report.
- [ ] `InvoiceGeneratorServiceImpl` accepts its calculator dependency via the constructor and Spring wires it.
- [ ] A `MockMvc`-based integration test posts each of the two `payloads/` fixtures and asserts the response JSON shape and key values.
- [ ] After this feature lands, the M2 work of fixing C-1/C-2/C-3 consists of flipping characterization tests' assertions (and pointing to this spec) rather than discovering behavior anew.
