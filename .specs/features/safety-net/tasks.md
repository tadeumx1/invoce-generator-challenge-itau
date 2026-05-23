# F-SAFETY-NET Tasks

**Spec:** `.specs/features/safety-net/spec.md`
**Design:** skipped — choices are standard (JUnit 5 + Mockito + MockMvc, `@ParameterizedTest` for brackets, JaCoCo plugin, surefire `@Tag` for the slow case). Captured inline in each task.
**Status:** Done (2026-05-22)

---

## Key design decisions (rolled in from skipped design phase)

1. **Calculator becomes `@Component` with an instance field (not `static`).** Bug C-1 is *preserved* because Spring's default singleton scope still shares state across requests in the same JVM. Characterization is intact; the conversion is purely a wiring change.
2. **Tax-rate tests mock the calculator.** SAFETY-01…11 verify *which rate the service picks*, not the math of `unitPrice × rate`. The math is covered separately in T1's calculator-level test. This isolation avoids the C-1 leak across parametrized cases.
3. **C-1 characterization (SAFETY-20) uses fresh, manually-instantiated objects.** A dedicated test instantiates `ProductTaxRateCalculator` and `InvoiceGeneratorServiceImpl` directly (no Spring) so it deterministically observes the accumulation across two calls without any cross-test contamination.
4. **HTTP integration test uses `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`.** Otherwise SAFETY-25 would see leftover items from SAFETY-24 because of the singleton-scoped calculator. The dirties-context overhead is acceptable for the 2–3 HTTP tests; M2 removes the need by fixing C-1.
5. **Slow test (SAFETY-23) is tagged `@Tag("slow")` and excluded by surefire default.** Run on demand with `./mvnw test -Dgroups=slow`.
6. **JaCoCo is added in measurement mode only.** No `check` goal, no thresholds — that gate ships in F-UPGRADE.

---

## Execution Plan

### Phase 1: Foundation (mostly parallel)

```
start ─┬─→ T1 ──→ T2
       ├─→ T3
       └─→ T4
```

T1 and T2 are sequential (T2 modifies the constructor whose signature depends on T1's bean conversion). T3 and T4 are independent and may run alongside T1+T2.

### Phase 2: Service-level tests (parallel)

```
            ┌─→ T5  [P]
            ├─→ T6  [P]
            ├─→ T7  [P]
            ├─→ T8  [P]
T2 + T4 ────┼─→ T9  [P]
            ├─→ T10 [P]
            ├─→ T11 [P]
            ├─→ T12 [P]
            └─→ T13 [P]
```

All depend on T2 (mock-capable service) and T4 (test data builders). Different files; parallel-safe.

### Phase 3: HTTP integration (sequential)

```
T1 + T2 + T3 + T4 ─→ T14
```

Single Spring context; not [P] with anything in Phase 2 that uses `@SpringBootTest` (none do).

---

## Task Breakdown

### T1: Convert `ProductTaxRateCalculator` to a Spring `@Component` and add calculator-level unit tests

**What:** Annotate `ProductTaxRateCalculator` as `@Component`; remove `static` from `invoiceItemList` so it becomes an instance field (bug C-1 is preserved via singleton sharing). Add `ProductTaxRateCalculatorTest` covering: (a) one item in → one `InvoiceItem` out with `itemTaxValue = unitPrice * rate`, (b) field copying (`itemId`, `description`, `unitPrice`, `quantity` round-trip), (c) characterization that two `calculateTax` calls on the same instance return a cumulative list.
**Where:**
- modify `src/main/java/br/com/itau/invoicegenerator/service/ProductTaxRateCalculator.java`
- create `src/test/java/br/com/itau/invoicegenerator/service/ProductTaxRateCalculatorTest.java`

**Depends on:** none
**Reuses:** existing `InvoiceItem`, `Item` builders.
**Requirement:** prerequisite for SAFETY-27/28/29; characterization aspect partially covers SAFETY-20 at the calculator level (full coverage in T11).

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] `ProductTaxRateCalculator` has `@Component` and `invoiceItemList` is a non-`static` `private final` field.
- [ ] No call site has changed signature (still `calculateTax(List<Item>, double)`).
- [ ] New `ProductTaxRateCalculatorTest` has 3 passing tests.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test`
- [ ] Test count: ≥ 3 new tests pass (no silent deletions of existing).

**Tests:** unit
**Gate:** quick

**Commit:** `refactor(safety-net): make ProductTaxRateCalculator a Spring bean with instance state (T1)`

---

### T2: Inject `ProductTaxRateCalculator` via constructor into `InvoiceGeneratorServiceImpl` and rewrite the existing test to actually use the mock

**What:** Add a constructor taking `ProductTaxRateCalculator`; remove `new ProductTaxRateCalculator()` inside `generateInvoice`. Annotate the constructor or class so Spring injects it (constructor injection is auto-detected; explicit `@Autowired` not required on a single-constructor class). Rewrite the two existing tests in `InvoiceGeneratorServiceImplTest` so the `@Mock` is now actually observed by the SUT (the test now asserts the service called `calculateTax(any(), eq(expectedRate))` on the mock and returned the mock's stubbed list).
**Where:**
- modify `src/main/java/br/com/itau/invoicegenerator/service/impl/InvoiceGeneratorServiceImpl.java`
- modify `src/test/java/br/com/itau/invoicegenerator/InvoiceGeneratorServiceImplTest.java`

**Depends on:** T1
**Reuses:** existing Mockito patterns (`@InjectMocks`, `@Mock`, `MockitoAnnotations.openMocks`).
**Requirement:** SAFETY-27, SAFETY-28, SAFETY-29.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] `InvoiceGeneratorServiceImpl` has a single public constructor accepting `ProductTaxRateCalculator`; no internal `new ProductTaxRateCalculator()`.
- [ ] Spring context still wires the service successfully (`InvoiceGeneratorApplicationTests.contextLoads` passes).
- [ ] The two existing test methods in `InvoiceGeneratorServiceImplTest` use `when(calculator.calculateTax(...))` stubs that are *observed* by the SUT.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test`
- [ ] Test count: same 3 service tests + 3 calculator tests from T1 = 6 minimum.

**Tests:** unit
**Gate:** quick

**Commit:** `refactor(safety-net): inject ProductTaxRateCalculator via constructor + fix misleading mock (T2)`

---

### T3: Add JaCoCo plugin and surefire `@Tag("slow")` exclusion to `pom.xml`

**What:** Add the `jacoco-maven-plugin` (latest stable for Java 11) with `prepare-agent` + `report` goals bound to `verify`. Add surefire `<configuration><excludedGroups>slow</excludedGroups></configuration>` so default test runs skip the slow characterization tests. Do not configure any coverage threshold (`check` goal omitted — gate ships in F-UPGRADE).
**Where:** modify `pom.xml`

**Depends on:** none
**Reuses:** existing `<build><plugins>` section.
**Requirement:** SAFETY-30, SAFETY-31; enables SAFETY-23 (slow exclusion).

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] `pom.xml` contains `jacoco-maven-plugin` with `prepare-agent` and `report` executions.
- [ ] `pom.xml` contains surefire `<excludedGroups>slow</excludedGroups>` (default-profile only).
- [ ] `JAVA_HOME=…temurin-11.0.20… ./mvnw verify` succeeds and produces `target/site/jacoco/index.html`.
- [ ] Default `./mvnw test` does not run any test tagged `@Tag("slow")`.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw verify`

**Tests:** none (build configuration; verified by JaCoCo report existence)
**Gate:** build

**Commit:** `chore(safety-net): add JaCoCo report and surefire @Tag("slow") exclusion (T3)`

---

### T4: Test data builders for `Order`, `Recipient`, `Address`, `Item`

**What:** Three small builder utility classes under `src/test/java/.../testsupport/` (NOT under main): `Orders` (factory methods `fisica(double total)`, `juridica(double total, CompanyTaxRegime regime)`), `Addresses` (`entrega(Region)`, `cobranca(Region)`), `Items` (`item(double unitPrice, int quantity)`). These provide one-liner construction across the entire test suite.
**Where:**
- create `src/test/java/br/com/itau/invoicegenerator/testsupport/Orders.java`
- create `src/test/java/br/com/itau/invoicegenerator/testsupport/Addresses.java`
- create `src/test/java/br/com/itau/invoicegenerator/testsupport/Items.java`

**Depends on:** none (only depends on the existing model classes)
**Reuses:** Lombok `@Builder` on the model classes.
**Requirement:** SAFETY-32.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] Three builder classes compile and are used by at least T5 to confirm the API is ergonomic.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test-compile`

**Tests:** none (test utility — exercised by every later task that uses them)
**Gate:** quick

**Commit:** `test(safety-net): add Orders/Addresses/Items test data builders (T4)`

---

### T5: Tax-rate selection tests for `FISICA` brackets `[P]`

**What:** New parametrized test class verifying the service picks the right rate for FISICA × totalItemsValue ∈ {0, 499.99, 500, 1000, 2000, 2000.01, 3000, 3500, 3500.01, 10000}. Calculator is mocked; assertion is `verify(calculator).calculateTax(any(), eq(expectedRate))`.
**Where:** create `src/test/java/br/com/itau/invoicegenerator/service/TaxRateSelectionFisicaTest.java`

**Depends on:** T2, T4
**Reuses:** `Orders.fisica`, `Addresses.entrega`, Mockito patterns from T2.
**Requirement:** SAFETY-01, SAFETY-02, SAFETY-03, SAFETY-04, SAFETY-05.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] `@ParameterizedTest` with `@CsvSource` covering the 10 input values above and expected rates {0, 0, 0.12, 0.12, 0.12, 0.15, 0.15, 0.15, 0.17, 0.17}.
- [ ] All cases pass.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test -Dtest=TaxRateSelectionFisicaTest`
- [ ] Test count: ≥ 10 parametrized cases.

**Tests:** unit
**Gate:** quick

**Commit:** `test(safety-net): cover FISICA tax-rate brackets (T5)`

---

### T6: Tax-rate selection tests for `JURIDICA × SIMPLES_NACIONAL` `[P]`

**What:** Same shape as T5; values across the 4 brackets {0, 999.99, 1000, 2000, 2000.01, 5000, 5000.01, 10000} → expected rates {0.03, 0.03, 0.07, 0.07, 0.13, 0.13, 0.19, 0.19}.
**Where:** create `src/test/java/br/com/itau/invoicegenerator/service/TaxRateSelectionSimplesNacionalTest.java`

**Depends on:** T2, T4
**Reuses:** same as T5.
**Requirement:** SAFETY-06, SAFETY-07, SAFETY-08, SAFETY-09.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] ≥ 8 parametrized cases pass.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test -Dtest=TaxRateSelectionSimplesNacionalTest`

**Tests:** unit
**Gate:** quick

**Commit:** `test(safety-net): cover SIMPLES_NACIONAL tax-rate brackets (T6)`

---

### T7: Tax-rate selection tests for `JURIDICA × LUCRO_REAL` `[P]`

**What:** Same shape as T6; 4 brackets → expected rates {0.03, 0.09, 0.15, 0.20}.
**Where:** create `src/test/java/br/com/itau/invoicegenerator/service/TaxRateSelectionLucroRealTest.java`

**Depends on:** T2, T4
**Requirement:** SAFETY-10.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] ≥ 8 parametrized cases pass.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test -Dtest=TaxRateSelectionLucroRealTest`

**Tests:** unit
**Gate:** quick

**Commit:** `test(safety-net): cover LUCRO_REAL tax-rate brackets (T7)`

---

### T8: Tax-rate selection tests for `JURIDICA × LUCRO_PRESUMIDO` `[P]`

**What:** Same shape as T7; 4 brackets → expected rates {0.03, 0.09, 0.16, 0.20}.
**Where:** create `src/test/java/br/com/itau/invoicegenerator/service/TaxRateSelectionLucroPresumidoTest.java`

**Depends on:** T2, T4
**Requirement:** SAFETY-11.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] ≥ 8 parametrized cases pass.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test -Dtest=TaxRateSelectionLucroPresumidoTest`

**Tests:** unit
**Gate:** quick

**Commit:** `test(safety-net): cover LUCRO_PRESUMIDO tax-rate brackets (T8)`

---

### T9: Freight multiplier tests for the 5 regions + `COBRANCA_ENTREGA` purpose `[P]`

**What:** Parametrized test: feed an order with `freightValue=100.0` and `Address(purpose=ENTREGA, region=R)` for R in {NORTE, NORDESTE, CENTRO_OESTE, SUDESTE, SUL}; assert `invoice.freightValue` equals the documented multiplier × 100 within 1e-6 tolerance. Add one separate case verifying that `purpose=COBRANCA_ENTREGA` also triggers the region lookup.
**Where:** create `src/test/java/br/com/itau/invoicegenerator/service/FreightMultiplierTest.java`

**Depends on:** T2, T4
**Reuses:** `Addresses.entrega`, `Addresses.cobrancaEntrega` (helper added in T4).
**Requirement:** SAFETY-12, SAFETY-13, SAFETY-14, SAFETY-15, SAFETY-16, SAFETY-17.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] 5 region cases + 1 COBRANCA_ENTREGA case = 6 passing tests.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test -Dtest=FreightMultiplierTest`

**Tests:** unit
**Gate:** quick

**Commit:** `test(safety-net): cover freight multipliers per region (T9)`

---

### T10: Characterization tests for C-3 (missing-region freight = 0) `[P]`

**What:** Two tests asserting the current buggy behavior: (1) no address with `purpose ∈ {ENTREGA, COBRANCA_ENTREGA}` → `invoice.freightValue == 0.0`; (2) matching address has `region=null` → `invoice.freightValue == 0.0` after F-CLEAN T11's null-safe lookup. Each test name includes a comment block citing C-3 and `business-rules.md` §6.3, so M2 sees the intent.
**Where:** create `src/test/java/br/com/itau/invoicegenerator/service/characterization/MissingRegionFreightCharacterizationTest.java`

**Depends on:** T2, T4
**Requirement:** SAFETY-18, SAFETY-19.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] 2 passing tests, each with a Javadoc-level comment marking them as characterizations of C-3.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test -Dtest=MissingRegionFreightCharacterizationTest`

**Tests:** unit
**Gate:** quick

**Commit:** `test(safety-net): characterize C-3 missing-region freight = 0 (T10)`

---

### T11: Characterization test for C-1 (static-list accumulation across calls) `[P]`

**What:** Single test method that constructs `ProductTaxRateCalculator` and `InvoiceGeneratorServiceImpl` manually (no Spring, no mocks), invokes `generateInvoice` twice on the same service instance with single-item orders, and asserts the second invoice's `items.size() == 2`. Includes the same Javadoc citation block as T10.
**Where:** create `src/test/java/br/com/itau/invoicegenerator/service/characterization/StaticListAccumulationCharacterizationTest.java`

**Depends on:** T1, T2, T4
**Reuses:** `Orders.fisica`, `Addresses.entrega`.
**Requirement:** SAFETY-20.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] Test passes today; comment says "M2 flips this assertion to `== 1`".
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test -Dtest=StaticListAccumulationCharacterizationTest`

**Tests:** unit
**Gate:** quick

**Commit:** `test(safety-net): characterize C-1 cross-call item accumulation (T11)`

---

### T12: Characterization tests for C-2 (`taxRegime ∈ {OUTROS, null}` → empty items) `[P]`

**What:** Two tests: (1) JURIDICA with `taxRegime=OUTROS` → `invoice.items` is empty; (2) JURIDICA with `taxRegime=null` → `invoice.items` is empty. Mocked calculator NOT used here because the buggy path bypasses the calculator entirely — easier to verify by inspecting `invoice.items`.
**Where:** create `src/test/java/br/com/itau/invoicegenerator/service/characterization/UnhandledTaxRegimeCharacterizationTest.java`

**Depends on:** T2, T4
**Requirement:** SAFETY-21, SAFETY-22.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] 2 passing tests with citation comments to C-2 / §6.2.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test -Dtest=UnhandledTaxRegimeCharacterizationTest`

**Tests:** unit
**Gate:** quick

**Commit:** `test(safety-net): characterize C-2 OUTROS/null taxRegime → empty items (T12)`

---

### T13: Characterization test for C-6 (>5 items wall-clock ≥ 5s) `[P]`

**What:** Single `@Test @Tag("slow")` method that submits an order with 6 items and asserts `Duration.between(start, end).toMillis() >= 5000`. Excluded from default suite by surefire config from T3. Documented as the trigger for F-DEFECTS-PERFORMANCE's async-dispatch fix.
**Where:** create `src/test/java/br/com/itau/invoicegenerator/service/characterization/SlowDeliveryCharacterizationTest.java`

**Depends on:** T2, T3, T4
**Requirement:** SAFETY-23.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] Test tagged `@Tag("slow")`.
- [ ] Default `./mvnw test` does NOT execute this test (excluded by T3 surefire config).
- [ ] On-demand run executes it and passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test -Dgroups=slow`
- [ ] Gate check passes for default profile (test absent from output): `JAVA_HOME=…temurin-11.0.20… ./mvnw test`

**Tests:** unit
**Gate:** quick

**Commit:** `test(safety-net): characterize C-6 >5-items 5s sleep (T13)`

---

### T14: MockMvc integration test with both sample payloads

**What:** `@SpringBootTest` + `@AutoConfigureMockMvc` + `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` test class with two methods: one POSTing `teste-pf.json`, one POSTing `teste-pj-simples.json`. Each asserts HTTP 200, `Content-Type: application/json`, presence of the snake_case Portuguese keys (`id_nota_fiscal`, `data`, `valor_total_itens`, `valor_frete`, `itens`, `destinatario`), and the documented expected values from spec §SAFETY-24/25.
**Where:** create `src/test/java/br/com/itau/invoicegenerator/web/InvoiceControllerIntegrationTest.java`

**Depends on:** T1, T2, T3, T4
**Reuses:** `paylods/teste-pf.json`, `paylods/teste-pj-simples.json` (read via classpath resources).
**Requirement:** SAFETY-24, SAFETY-25, SAFETY-26.

**Tools:**
- MCP: none
- Skill: none

**Done when:**
- [ ] 2 passing `@Test` methods, each posting a fixture JSON and asserting response shape + values.
- [ ] JSON response uses snake_case Portuguese keys exclusively (no `invoiceId`, no `unitPrice` keys in the output).
- [ ] `@DirtiesContext(classMode = BEFORE_EACH_TEST_METHOD)` is applied so SAFETY-25 isn't polluted by SAFETY-24.
- [ ] Gate check passes: `JAVA_HOME=…temurin-11.0.20… ./mvnw test -Dtest=InvoiceControllerIntegrationTest`
- [ ] Full build green: `JAVA_HOME=…temurin-11.0.20… ./mvnw verify` (with JaCoCo report produced).

**Tests:** integration
**Gate:** full

**Commit:** `test(safety-net): MockMvc end-to-end with both sample payloads (T14)`

---

## Parallel Execution Map

```
Phase 1 (Foundation):
  T1 ──→ T2
  T3 (parallel with T1, T2)
  T4 (parallel with T1, T2, T3)

Phase 2 (Service tests — parallel after T2 + T4):
  ├── T5  [P]
  ├── T6  [P]
  ├── T7  [P]
  ├── T8  [P]
  ├── T9  [P]
  ├── T10 [P]
  ├── T11 [P]   (also requires T1 directly)
  ├── T12 [P]
  └── T13 [P]   (also requires T3)

Phase 3 (Integration):
  T14 (after T1+T2+T3+T4)
```

**Parallelism constraint:** All [P] tasks in Phase 2 write to different files and use mocks or direct instantiation — no shared mutable state at write time. T11 deliberately bypasses the singleton-scoped bean so it doesn't observe other tests' side effects. T13 is tagged `@Tag("slow")` so it doesn't slow down the default suite.

---

## Task Granularity Check

| Task                                                  | Scope                                                | Status         |
| ----------------------------------------------------- | ---------------------------------------------------- | -------------- |
| T1: calc → bean + calc test                           | 1 modify + 1 create (one cohesive unit)              | ✅ Granular    |
| T2: service ctor + rewrite existing test              | 1 modify + 1 modify                                  | ✅ Granular    |
| T3: pom changes (JaCoCo + slow exclusion)             | 1 modify                                             | ✅ Granular    |
| T4: 3 builder helper classes                          | 3 files but one cohesive concept (test fixtures)     | ⚠️ Borderline → kept as one because the API is co-designed |
| T5: FISICA brackets                                   | 1 test file                                          | ✅ Granular    |
| T6: SIMPLES_NACIONAL brackets                         | 1 test file                                          | ✅ Granular    |
| T7: LUCRO_REAL brackets                               | 1 test file                                          | ✅ Granular    |
| T8: LUCRO_PRESUMIDO brackets                          | 1 test file                                          | ✅ Granular    |
| T9: freight per region                                | 1 test file                                          | ✅ Granular    |
| T10: C-3 characterization                             | 1 test file                                          | ✅ Granular    |
| T11: C-1 characterization                             | 1 test file                                          | ✅ Granular    |
| T12: C-2 characterization                             | 1 test file                                          | ✅ Granular    |
| T13: C-6 slow characterization                        | 1 test file                                          | ✅ Granular    |
| T14: MockMvc integration                              | 1 test file                                          | ✅ Granular    |

All 14 pass. T4 is the only borderline call and is justified by the cohesion of test-fixture API design.

---

## Diagram-Definition Cross-Check

| Task | Depends On (task body) | Diagram Shows | Status |
| ---- | ---------------------- | ------------- | ------ |
| T1   | None                   | start → T1    | ✅ Match |
| T2   | T1                     | T1 → T2       | ✅ Match |
| T3   | None                   | start → T3 (parallel with T1/T2) | ✅ Match |
| T4   | None                   | start → T4 (parallel with T1/T2/T3) | ✅ Match |
| T5   | T2, T4                 | T2/T4 → T5 [P]| ✅ Match |
| T6   | T2, T4                 | T2/T4 → T6 [P]| ✅ Match |
| T7   | T2, T4                 | T2/T4 → T7 [P]| ✅ Match |
| T8   | T2, T4                 | T2/T4 → T8 [P]| ✅ Match |
| T9   | T2, T4                 | T2/T4 → T9 [P]| ✅ Match |
| T10  | T2, T4                 | T2/T4 → T10 [P] | ✅ Match |
| T11  | T1, T2, T4             | T1/T2/T4 → T11 [P] | ✅ Match (annotation in Parallel Execution Map notes the extra T1 dep) |
| T12  | T2, T4                 | T2/T4 → T12 [P] | ✅ Match |
| T13  | T2, T3, T4             | T2/T3/T4 → T13 [P] | ✅ Match (annotation in Parallel Execution Map notes the extra T3 dep) |
| T14  | T1, T2, T3, T4         | T1/T2/T3/T4 → T14 | ✅ Match |

All 14 pass. T11 and T13 have extra dependencies (T1 and T3 respectively) beyond the Phase 2 "T2+T4" baseline; both are explicitly annotated under the Parallel Execution Map.

---

## Test Co-location Validation

Per `.specs/codebase/TESTING.md` Test Coverage Matrix:

| Code Layer                  | Required Test Type | Notes |
| --------------------------- | ------------------ | ----- |
| Service layer               | unit               |       |
| Spring wiring               | integration        |       |
| Web layer                   | integration        | new in this feature |
| Test utilities              | n/a                | no requirement |
| Build configuration         | n/a                | no requirement |

| Task | Code Layer Created/Modified                                | Matrix Requires | Task Says   | Status |
| ---- | ---------------------------------------------------------- | --------------- | ----------- | ------ |
| T1   | Service layer (calculator)                                 | unit            | unit        | ✅ OK  |
| T2   | Service layer (service)                                    | unit            | unit        | ✅ OK  |
| T3   | Build configuration (`pom.xml`)                            | none            | none        | ✅ OK  |
| T4   | Test utilities                                             | none            | none        | ✅ OK  |
| T5   | Tests only (no production code)                            | n/a             | unit        | ✅ OK (tests for T2's layer) |
| T6   | Tests only                                                 | n/a             | unit        | ✅ OK  |
| T7   | Tests only                                                 | n/a             | unit        | ✅ OK  |
| T8   | Tests only                                                 | n/a             | unit        | ✅ OK  |
| T9   | Tests only                                                 | n/a             | unit        | ✅ OK  |
| T10  | Tests only (characterization)                              | n/a             | unit        | ✅ OK  |
| T11  | Tests only (characterization)                              | n/a             | unit        | ✅ OK  |
| T12  | Tests only (characterization)                              | n/a             | unit        | ✅ OK  |
| T13  | Tests only (characterization)                              | n/a             | unit        | ✅ OK  |
| T14  | Web layer + Spring wiring                                  | integration     | integration | ✅ OK  |

All 14 pass. T1 and T2 each modify a layer that requires unit tests and include those unit tests inline (not deferred to T5+). T5–T13 are pure test-additions for layers whose production code was already tested by T1/T2 — these add the wider business-rule coverage and characterization in atomic, parallelizable chunks. T14 creates the new web-layer integration coverage in the task that wires it.

---

## Tools availability snapshot

For information only — to be re-confirmed before Execute (per skill step 6):

- **MCPs available in this project:** Gmail, Calendar, Drive, IDE diagnostics. None apply to Java/Maven code editing.
- **Skills available:** `tlc-spec-driven` (this one), `code-review`, `verify`, `run`, `init`, `update-config`, `fewer-permission-prompts`, `loop`, `schedule`, `claude-api`, `review`, `security-review`, `keybindings-help`.

**Suggested skill usage per task:**

- T1, T2: no skill needed during writing; `code-review` *after* both complete is recommended (catches Lombok / Spring wiring slips).
- T3: no skill.
- T4: no skill.
- T5–T13: no skill needed during writing.
- T14: `verify` after completion to manually `curl` the running app against the two fixtures.
- After all tasks: `code-review` of the whole feature diff before opening a PR. Optionally `/ultrareview <PR#>` (user-triggered) once the PR exists.

---

## Coverage rollup (post-tasks)

After all 14 tasks land:

| SAFETY-NN | Mapped to task | Status |
| --------- | -------------- | ------ |
| SAFETY-01 to 05  | T5  | Pending |
| SAFETY-06 to 09  | T6  | Pending |
| SAFETY-10        | T7  | Pending |
| SAFETY-11        | T8  | Pending |
| SAFETY-12 to 17  | T9  | Pending |
| SAFETY-18 to 19  | T10 | Pending |
| SAFETY-20        | T11 (plus partial characterization in T1) | Pending |
| SAFETY-21 to 22  | T12 | Pending |
| SAFETY-23        | T13 | Pending |
| SAFETY-24 to 26  | T14 | Pending |
| SAFETY-27 to 29  | T2  | Pending |
| SAFETY-30 to 31  | T3  | Pending |
| SAFETY-32        | T4  | Pending |

**Coverage:** 32 requirements, 32 mapped to tasks, 0 unmapped ✅.

---

## Acceptance for the feature as a whole

Defined by spec §"Success Criteria" — restated here for the task list:

- [ ] `./mvnw test` (default profile) produces ≥ 25 green tests, identical across 3 consecutive runs.
- [ ] `./mvnw test -Dgroups=slow` adds the one slow test (T13) and it passes.
- [ ] `./mvnw verify` produces `target/site/jacoco/index.html`.
- [ ] Each of C-1, C-2, C-3, C-6 has at least one characterization test referenced by SAFETY-NN in source.
- [ ] After T2, `InvoiceGeneratorServiceImpl` accepts the calculator via constructor; Spring wires it; existing test's mock is observed by the SUT.
- [ ] T14's two HTTP integration tests pass with the unchanged sample fixtures.
- [ ] STATE.md updated: F-SAFETY-NET moves PLANNED → COMPLETE in ROADMAP.md.
