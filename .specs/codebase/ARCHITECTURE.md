# Architecture

**Pattern:** Single-module Spring Boot API organized as Clean Architecture: domain model/ports, application use case, inbound/outbound adapters.

## High-Level Structure

```
HTTP request
   |
   v
adapter/web/InvoiceController
   |
   v
application/GenerateInvoiceUseCase
application/GenerateInvoiceInteractor
   |
   +-- domain/service/TaxRateTable
   +-- domain/port/TaxRateCalculator
   +-- domain/port/FreightCalculator
   +-- domain/port/StockPort
   +-- domain/port/InvoiceRegistrationPort
   +-- domain/port/DeliveryPort
   +-- domain/port/AccountsReceivablePort
        ^
        |
adapter/config/ApplicationBeanConfig wires concrete adapters
adapter/integration/{stock,registration,delivery,finance}
```

## Layer Rules

- `domain/` contains models, ports, and business-rule services. It has no Spring or Jackson imports.
- `application/` contains use case contracts and interactors. It has no Spring or Jackson imports.
- `adapter/` owns framework and transport concerns: Spring MVC, JSON DTOs, bean wiring, and simulated external integrations.
- `InvoiceGeneratorApplication` is the Spring Boot entry point only.

## Current Data Flow

1. Client `POST`s Portuguese snake_case JSON to `/api/orders/generate-invoice`.
2. `adapter/web` deserializes into DTOs and maps DTOs to domain `Order`.
3. `GenerateInvoiceInteractor.generateInvoice(order)` selects the tax rate via `TaxRateTable`.
4. If tax-regime/person-type input is invalid, the domain throws `InvalidInvoiceOrderException` and the web adapter returns HTTP 400.
5. It delegates per-item tax math to `TaxRateCalculator`.
6. It computes freight via `FreightCalculator`; missing/null delivery region is rejected with the same typed exception.
7. Calculated money is rounded via `Money.rounded` (`BigDecimal`, scale 2, `HALF_EVEN`).
8. It builds the domain `Invoice`.
9. It calls outbound ports synchronously in legacy order: stock, registration, delivery, finance.
10. The controller maps the domain `Invoice` back to response DTOs, preserving the JSON contract.

## Current Functional Policy

- C-1 is fixed: `LegacyProductTaxRateCalculator` is stateless per call.
- C-2 is fixed: JURIDICA + `OUTROS`/null rejects with HTTP 400.
- C-3 is fixed: missing/null delivery region rejects with HTTP 400.
- C-4 is fixed: monetary fields use `BigDecimal`, with scale 2 `HALF_EVEN` rounding for calculated money.
- Integrations still sleep synchronously, including delivery's +5s trap for invoices with more than 5 items (C-6).

## Code Organization

```
src/main/java/br/com/itau/invoicegenerator/
├── InvoiceGeneratorApplication.java
├── adapter/
│   ├── config/
│   ├── integration/{delivery,finance,registration,stock}/
│   └── web/
│       └── dto/
├── application/
├── domain/
│   ├── model/
│   ├── port/
│   └── service/
```

Full canonical business rules: `docs/business-rules.md`.
