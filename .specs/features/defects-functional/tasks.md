# F-DEFECTS-FUNCTIONAL Tasks

**Spec:** `.specs/features/defects-functional/spec.md`
**Design:** skipped — implementation is localized by the F-CLEAN boundaries. Decisions are captured inline below.
**Status:** Done (2026-05-23)

---

## Key Design Decisions

1. **Invalid fiscal/freight input rejects with HTTP 400.** Silent fallback was the defect for C-2 and C-3. A typed domain exception plus `@RestControllerAdvice` gives a predictable API response without putting Spring in the domain layer.
2. **Domain money uses `BigDecimal`, API remains numeric.** Jackson serializes `BigDecimal` as a JSON number, so clients keep the same payload shape while domain arithmetic becomes deterministic.
3. **Rounding policy is centralized.** `Money.rounded` applies scale 2 and `RoundingMode.HALF_EVEN` for calculated monetary results.
4. **Tax rates are `BigDecimal` too.** `TaxRate.of("0.19")` avoids binary floating-point artifacts in rate multiplication.
5. **C-6 is deliberately untouched.** Side effects remain synchronous and slow-path behavior stays covered by the slow profile.

---

## Execution Plan

```
start
  ├─→ T1: stateless tax item calculator
  ├─→ T2: invalid tax-regime policy
  ├─→ T3: invalid delivery-region policy
  ├─→ T4: BigDecimal money migration
  ├─→ T5: HTTP 400 adapter handling
  └─→ T6: docs + verification
```

T1 through T5 touch separate concerns but are verified together because C-4 changes method signatures across the same graph.

---

## Task Breakdown

### T1: Make `LegacyProductTaxRateCalculator` stateless

**What:** Remove the shared request item list from the calculator. Build and return a fresh `List<InvoiceItem>` for every `calculateTax` call.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/domain/service/LegacyProductTaxRateCalculator.java`
- `src/test/java/br/com/itau/invoicegenerator/service/LegacyProductTaxRateCalculatorTest.java`
- `src/test/java/br/com/itau/invoicegenerator/service/characterization/StaticListAccumulationCharacterizationTest.java`

**Requirement:** DEF-FUNC-01, DEF-FUNC-02, DEF-FUNC-03

**Done when:**
- [x] Two sequential calculator/use-case calls each return only their own items.
- [x] C-1 characterization now asserts no leak.
- [x] No mutable request list remains on the calculator bean.

**Tests:** unit
**Gate:** `./mvnw test -Dtest='LegacyProductTaxRateCalculatorTest,StaticListAccumulationCharacterizationTest'`

---

### T2: Reject unsupported/missing juridica tax regimes

**What:** Change `TaxRateTable` so `JURIDICA + OUTROS` and `JURIDICA + null` throw `InvalidInvoiceOrderException` instead of returning no rate.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/domain/service/TaxRateTable.java`
- `src/main/java/br/com/itau/invoicegenerator/domain/exception/InvalidInvoiceOrderException.java`
- `src/test/java/br/com/itau/invoicegenerator/service/characterization/UnhandledTaxRegimeCharacterizationTest.java`

**Requirement:** DEF-FUNC-04, DEF-FUNC-05, DEF-FUNC-07

**Done when:**
- [x] `OUTROS` throws code `UNSUPPORTED_TAX_REGIME`.
- [x] null tax regime throws code `INVALID_TAX_REGIME`.
- [x] Supported tax-regime bracket tests still pass.

**Tests:** unit
**Gate:** `./mvnw test -Dtest='UnhandledTaxRegimeCharacterizationTest,*TaxRateSelection*Test'`

---

### T3: Reject missing/null delivery region

**What:** Change `LegacyFreightCalculator` so absent delivery address or delivery address with `region=null` throws `InvalidInvoiceOrderException`.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/domain/service/LegacyFreightCalculator.java`
- `src/test/java/br/com/itau/invoicegenerator/service/characterization/MissingRegionFreightCharacterizationTest.java`
- `src/test/java/br/com/itau/invoicegenerator/service/FreightMultiplierTest.java`

**Requirement:** DEF-FUNC-08, DEF-FUNC-09, DEF-FUNC-11

**Done when:**
- [x] No delivery address throws code `INVALID_DELIVERY_REGION`.
- [x] Null delivery region throws code `INVALID_DELIVERY_REGION`.
- [x] Valid region multipliers still pass.

**Tests:** unit
**Gate:** `./mvnw test -Dtest='MissingRegionFreightCharacterizationTest,FreightMultiplierTest'`

---

### T4: Migrate domain/API money to `BigDecimal`

**What:** Replace monetary `double` fields in domain models and web DTOs with `BigDecimal`. Update ports, services, test builders, and assertions.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/domain/model/{Order,Invoice,Item,InvoiceItem,Money,TaxRate}.java`
- `src/main/java/br/com/itau/invoicegenerator/adapter/web/dto/{OrderDto,InvoiceDto,ItemDto,InvoiceItemDto}.java`
- `src/main/java/br/com/itau/invoicegenerator/domain/port/{TaxRateCalculator,FreightCalculator}.java`
- `src/test/java/br/com/itau/invoicegenerator/testsupport/**`

**Requirement:** DEF-FUNC-12, DEF-FUNC-13, DEF-FUNC-14, DEF-FUNC-15, DEF-FUNC-16

**Done when:**
- [x] Domain and DTO monetary fields compile as `BigDecimal`.
- [x] Tax and freight calculations round through `Money.rounded`.
- [x] JSON integration tests still assert numeric payload values.

**Tests:** unit + HTTP integration
**Gate:** `./mvnw test`

---

### T5: Map domain validation failures to HTTP 400

**What:** Add an adapter-level exception handler that maps `InvalidInvoiceOrderException` into a stable error response with Portuguese JSON keys.

**Where:**
- `src/main/java/br/com/itau/invoicegenerator/adapter/web/ApiExceptionHandler.java`
- `src/main/java/br/com/itau/invoicegenerator/adapter/web/dto/ErrorResponseDto.java`
- `src/test/java/br/com/itau/invoicegenerator/web/InvoiceControllerIntegrationTest.java`

**Requirement:** DEF-FUNC-06, DEF-FUNC-10

**Done when:**
- [x] `OUTROS` fixture variant returns HTTP 400 with `codigo=UNSUPPORTED_TAX_REGIME`.
- [x] missing delivery region fixture variant returns HTTP 400 with `codigo=INVALID_DELIVERY_REGION`.
- [x] Domain/application layers still have no Spring or Jackson imports.

**Tests:** HTTP integration
**Gate:** `./mvnw test -Dtest=InvoiceControllerIntegrationTest`

---

### T6: Update spec-driven docs and run full verification

**What:** Mark C-1 through C-4 resolved in project/codebase docs, add this feature spec/tasks, and run the full gate.

**Where:**
- `.specs/features/defects-functional/{spec.md,tasks.md}`
- `.specs/project/{ROADMAP.md,STATE.md}`
- `.specs/codebase/{ARCHITECTURE.md,CONCERNS.md,CONVENTIONS.md,TESTING.md}`
- `docs/{business-rules.md,tax-rate-table.md}`
- `CLAUDE.md`

**Requirement:** documentation + traceability

**Done when:**
- [x] Roadmap marks F-DEFECTS-FUNCTIONAL complete.
- [x] Concerns C-1 through C-4 are marked resolved.
- [x] Full verification passes.

**Tests/Gates:**

```bash
./mvnw spotless:apply
./mvnw verify
./mvnw test -Pslow
grep -R "org.springframework\|com.fasterxml.jackson" -n \
  src/main/java/br/com/itau/invoicegenerator/domain \
  src/main/java/br/com/itau/invoicegenerator/application
```

---

## Verification Summary

- Fast tests: `./mvnw test` — passing, 56 tests.
- Full build gate: `./mvnw verify` — passing.
- Slow characterization: `./mvnw test -Pslow` — passing.
- Architecture check: no Spring/Jackson imports in domain/application.
