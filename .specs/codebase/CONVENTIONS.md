# Code Conventions

These are the current conventions after F-UPGRADE and F-CLEAN.

## Naming Conventions

**Files:** PascalCase, one public type per file, matching the type name.

**Packages:** lowercase under `br.com.itau.invoicegenerator`, organized by Clean Architecture layer:

- `domain.model`, `domain.port`, `domain.service`
- `application`
- `adapter.web`, `adapter.web.dto`
- `adapter.integration.*`
- `adapter.config`

**Classes:** PascalCase. Use case interfaces use action names such as `GenerateInvoiceUseCase`; concrete interactors use `Interactor`. Legacy-preserving services are prefixed with `Legacy` until M2 defect fixes replace them.

**Methods:** camelCase, verb-first. Examples: `generateInvoice`, `calculateTax`, `scheduleDelivery`.

**Enums:** constants remain in Portuguese because enum values are part of the JSON contract (`FISICA`, `SIMPLES_NACIONAL`, `COBRANCA_ENTREGA`).

## Layering

- Domain and application code must not import Spring or Jackson.
- JSON annotations and request/response DTOs belong in `adapter.web.dto`.
- Spring annotations belong in adapters/configuration only.
- Outbound side effects go through `domain.port` interfaces.

## Formatting / Imports

Java sources are formatted with Spotless + google-java-format. Checkstyle blocks wildcard, redundant, and unused imports. Use `./mvnw spotless:apply` for formatting and `./mvnw verify` as the full gate.

## Type Safety / Error Handling

Money is represented as `BigDecimal` in domain models and web DTOs. Calculated money must go through `Money.rounded`, which applies scale 2 with `RoundingMode.HALF_EVEN`. Tax rates should be created from strings through `TaxRate.of(...)`.

Domain validation failures use `InvalidInvoiceOrderException` with stable error codes; the web adapter maps those to HTTP 400. Nullability is not yet annotated. Integration sleeps still wrap `InterruptedException` as runtime exceptions; resilience/error modeling is deferred to M3.

## Tests

Prefer focused tests with explicit fakes over framework mocks. `TestUseCases` builds the application use case without Spring. Slow behavior is tagged `@Tag("slow")` and runs through `./mvnw test -Pslow`.
