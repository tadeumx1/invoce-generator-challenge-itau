# F-DEFECTS-FUNCTIONAL — Functional Defect Closure Specification

## Problem Statement

M1 left the invoice flow in a cleaner shape: tax selection, per-item tax math, freight calculation, and side effects now sit behind Clean Architecture boundaries. That made the first functional defect pass small enough to do safely.

This feature closes the correctness defects tracked as C-1 through C-4 in `CONCERNS.md`:

- C-1: the tax item calculator leaked invoice items across requests.
- C-2: `JURIDICA` recipients with `taxRegime=OUTROS` or a missing tax regime produced an invoice with `items=[]`.
- C-3: missing delivery region silently produced `freightValue=0`.
- C-4: domain money used primitive `double`.

The chosen policy for invalid fiscal/freight input is to reject the request with HTTP 400 and a typed error body. The chosen money policy is `BigDecimal` arithmetic in the domain with explicit BRL rounding: scale 2, `RoundingMode.HALF_EVEN`. JSON remains numeric on the wire.

## Goals

- [x] Make `LegacyProductTaxRateCalculator` stateless so every request gets a fresh item list.
- [x] Flip the C-1 characterization test from accumulated items to request isolation.
- [x] Reject `JURIDICA` + `taxRegime=OUTROS` with HTTP 400.
- [x] Reject `JURIDICA` + missing/null `taxRegime` with HTTP 400.
- [x] Reject missing delivery address / delivery address with `region=null` with HTTP 400.
- [x] Add a web error DTO with stable Portuguese JSON keys: `codigo`, `mensagem`.
- [x] Move monetary fields in domain and web DTOs to `BigDecimal`.
- [x] Round calculated item tax and freight to scale 2 with `HALF_EVEN`.
- [x] Keep the Portuguese snake_case request/response contract unchanged.
- [x] Keep side effects synchronous; C-6 remains for F-DEFECTS-PERFORMANCE.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Async side effects / outbox | Belongs in F-DEFECTS-PERFORMANCE and F-RESILIENCE. |
| Timeouts, retries, circuit breakers | Belongs in F-RESILIENCE. |
| Changing item tax to multiply by quantity | The legacy/documented rule ignores quantity; changing it would be a separate business decision. |
| Renaming `payloads/` to `payloads/` | Cosmetic C-7, deferred. |
| Full Bean Validation annotations | Useful, but this feature only addresses the documented C-1 to C-4 defects. |

---

## User Stories

### P1: Stateless per-item tax calculation

**User Story**: As an API consumer, I want one invoice request to contain only its own calculated items, so that another customer's previous invoice can never leak into the response.

**Acceptance Criteria**:

1. **WHEN** the same `LegacyProductTaxRateCalculator` instance processes two independent one-item requests **THEN** each result SHALL contain exactly one item.
2. **WHEN** `GenerateInvoiceInteractor` is called twice with a singleton calculator **THEN** the second invoice SHALL not include items from the first invoice.
3. **WHEN** `LegacyProductTaxRateCalculator.calculateTax` runs **THEN** it SHALL allocate a fresh result list for that call and SHALL not store mutable request data on the bean.

**Independent Test**:

```bash
./mvnw test -Dtest='LegacyProductTaxRateCalculatorTest,StaticListAccumulationCharacterizationTest'
```

---

### P1: Reject unsupported or missing juridica tax regime

**User Story**: As a downstream fiscal system, I want juridica orders without a supported tax regime to be rejected, so that the invoice cannot say it has a total item value while returning an empty calculated item list.

**Acceptance Criteria**:

4. **WHEN** `personType=JURIDICA` and `taxRegime=OUTROS` **THEN** domain tax-rate selection SHALL throw `InvalidInvoiceOrderException` with code `UNSUPPORTED_TAX_REGIME`.
5. **WHEN** `personType=JURIDICA` and `taxRegime=null` **THEN** domain tax-rate selection SHALL throw `InvalidInvoiceOrderException` with code `INVALID_TAX_REGIME`.
6. **WHEN** either invalid tax-regime case is sent through HTTP **THEN** the response SHALL be HTTP 400 with JSON fields `codigo` and `mensagem`.
7. **WHEN** `taxRegime` is one of `SIMPLES_NACIONAL`, `LUCRO_REAL`, or `LUCRO_PRESUMIDO` **THEN** the existing tax-rate brackets SHALL remain unchanged.

**Independent Test**:

```bash
./mvnw test -Dtest='UnhandledTaxRegimeCharacterizationTest,InvoiceControllerIntegrationTest,*TaxRateSelection*Test'
```

---

### P1: Reject missing delivery region

**User Story**: As a business owner, I want malformed delivery addresses rejected instead of silently charging zero freight, so that invoices do not hide freight inconsistencies.

**Acceptance Criteria**:

8. **WHEN** an order has no address with `purpose=ENTREGA` or `COBRANCA_ENTREGA` **THEN** freight calculation SHALL throw `InvalidInvoiceOrderException` with code `INVALID_DELIVERY_REGION`.
9. **WHEN** the matching delivery address has `region=null` **THEN** freight calculation SHALL throw the same typed exception.
10. **WHEN** the missing/null delivery region case is sent through HTTP **THEN** the response SHALL be HTTP 400 with `codigo=INVALID_DELIVERY_REGION`.
11. **WHEN** a valid delivery region exists **THEN** the five region multipliers SHALL remain unchanged, except for explicit money rounding to 2 decimals.

**Independent Test**:

```bash
./mvnw test -Dtest='MissingRegionFreightCharacterizationTest,FreightMultiplierTest,InvoiceControllerIntegrationTest'
```

---

### P1: BigDecimal money with explicit rounding

**User Story**: As a maintainer of fiscal calculations, I want domain monetary arithmetic to use `BigDecimal` with a visible rounding policy, so that tax/freight values are deterministic and reviewable.

**Acceptance Criteria**:

12. **WHEN** domain models represent money (`Order`, `Invoice`, `Item`, `InvoiceItem`) **THEN** monetary fields SHALL use `BigDecimal`.
13. **WHEN** web DTOs represent money **THEN** monetary fields SHALL use `BigDecimal` and still serialize as JSON numbers.
14. **WHEN** per-item tax is calculated **THEN** `itemTaxValue = unitPrice × taxRate`, rounded with scale 2 and `HALF_EVEN`.
15. **WHEN** freight is adjusted by region **THEN** `freightValue × multiplier` SHALL be rounded with scale 2 and `HALF_EVEN`.
16. **WHEN** `src/main/java` is searched for monetary primitive fields in domain models/DTOs **THEN** no money field SHALL remain as `double`.

**Independent Test**:

```bash
./mvnw test
grep -R "private double" -n src/main/java/br/com/itau/invoicegenerator/domain src/main/java/br/com/itau/invoicegenerator/adapter/web/dto
```

---

## Edge Cases

- **WHEN** `recipient.personType=null` **THEN** tax-rate selection rejects the request with `INVALID_PERSON_TYPE`.
- **WHEN** `freightValue=72` and `region=SUDESTE` **THEN** adjusted freight is `75.46` because `72 × 1.048 = 75.456` and the feature rounds to 2 decimals.
- **WHEN** a tax calculation produces a trailing zero, such as `138.70`, **THEN** JSON still returns a numeric value and clients must not depend on decimal formatting.
- **WHEN** the slow delivery path receives more than 5 items **THEN** it remains slow; that is C-6 and belongs to the next feature.

## Requirement Traceability

| Requirement ID | Story | Status |
| --- | --- | --- |
| DEF-FUNC-01 | P1 — Calculator is stateless per invocation | Verified |
| DEF-FUNC-02 | P1 — Use-case no longer leaks items between calls | Verified |
| DEF-FUNC-03 | P1 — Calculator stores no request list on the bean | Verified |
| DEF-FUNC-04 | P1 — `OUTROS` rejects with `UNSUPPORTED_TAX_REGIME` | Verified |
| DEF-FUNC-05 | P1 — null tax regime rejects with `INVALID_TAX_REGIME` | Verified |
| DEF-FUNC-06 | P1 — invalid tax regime maps to HTTP 400 | Verified |
| DEF-FUNC-07 | P1 — supported tax brackets unchanged | Verified |
| DEF-FUNC-08 | P1 — no delivery address rejects with `INVALID_DELIVERY_REGION` | Verified |
| DEF-FUNC-09 | P1 — null delivery region rejects with `INVALID_DELIVERY_REGION` | Verified |
| DEF-FUNC-10 | P1 — missing delivery region maps to HTTP 400 | Verified |
| DEF-FUNC-11 | P1 — valid freight multipliers preserved with rounding | Verified |
| DEF-FUNC-12 | P1 — domain money fields use `BigDecimal` | Verified |
| DEF-FUNC-13 | P1 — DTO money fields use `BigDecimal` and JSON numbers | Verified |
| DEF-FUNC-14 | P1 — item tax rounded scale 2 `HALF_EVEN` | Verified |
| DEF-FUNC-15 | P1 — freight rounded scale 2 `HALF_EVEN` | Verified |
| DEF-FUNC-16 | P1 — no monetary primitive fields remain in domain/DTOs | Verified |

## Success Criteria

- [x] `./mvnw test` passes with flipped C-1/C-2/C-3 expectations.
- [x] HTTP integration covers valid PF/PJ responses plus bad-request cases for unsupported tax regime and missing delivery region.
- [x] `./mvnw verify` passes after formatting.
- [x] `./mvnw test -Pslow` still passes, proving C-6 remains characterized for the next feature.
- [x] `CONCERNS.md`, `ROADMAP.md`, `STATE.md`, `business-rules.md`, and `tax-rate-table.md` describe C-1 to C-4 as resolved.
