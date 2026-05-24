# Project Structure

**Root:** `/Users/matheustadeu/geradornotafiscal`

## Directory Tree

```
.
├── .mvn/                 # Maven wrapper config
├── .specs/               # Spec-driven artifacts
│   ├── codebase/
│   ├── features/
│   └── project/
├── config/checkstyle/    # Checkstyle rules
├── docs/                 # Human-facing documentation
├── src/main/java/br/com/itau/invoicegenerator/
│   ├── InvoiceGeneratorApplication.java
│   ├── adapter/
│   │   ├── config/       # Spring bean composition
│   │   ├── integration/  # Simulated outbound systems
│   │   └── web/          # Spring MVC controller + JSON DTOs
│   ├── application/      # Use case API + interactor
│   └── domain/
│       ├── model/        # Domain models and enums
│       ├── port/         # Inversion boundaries
│       └── service/      # Domain services / rule tables
├── src/main/resources/
│   ├── application.properties
│   └── payloads/          # Misspelled sample payload directory, kept for compatibility
├── src/test/java/br/com/itau/invoicegenerator/
│   ├── GenerateInvoiceInteractorTest.java
│   ├── InvoiceGeneratorApplicationTests.java
│   ├── service/
│   ├── testsupport/
│   └── web/
├── CLAUDE.md
├── README.md
├── pom.xml
└── mvnw, mvnw.cmd
```

## Module Organization

### domain/

Pure Java domain code. `domain/model` contains `Order`, `Invoice`, `Recipient`, `Address`, `Item`, `InvoiceItem`, `Document`, and enums. `domain/port` defines outbound dependencies. `domain/service` contains `TaxRateTable`, `LegacyProductTaxRateCalculator`, and `LegacyFreightCalculator`.

### application/

Use case boundary. `GenerateInvoiceUseCase` is the application entry point; `GenerateInvoiceInteractor` orchestrates rules and ports.

### adapter/

Framework and outside-world code. `adapter/web` owns Spring MVC and JSON DTO mapping. `adapter/integration` contains the simulated stock, registration, delivery, and finance adapters. `adapter/config/ApplicationBeanConfig` wires the application graph.

### testsupport/

Reusable builders/fakes for tests, including `TestUseCases` and `RecordingTaxRateCalculator`, so unit tests can instantiate the use case without Spring.

## Where Things Live

- HTTP: `adapter/web/InvoiceController.java`
- JSON contract DTOs: `adapter/web/dto/*.java`
- Use case: `application/GenerateInvoiceUseCase.java`, `GenerateInvoiceInteractor.java`
- Tax-rate selection: `domain/service/TaxRateTable.java`
- Per-item tax math: `domain/service/LegacyProductTaxRateCalculator.java`
- Freight: `domain/service/LegacyFreightCalculator.java`
- Side-effect ports: `domain/port/*.java`
- Simulated integrations: `adapter/integration/**`
- Sample payloads: `src/main/resources/payloads/teste-pf.json`, `teste-pj-simples.json`
