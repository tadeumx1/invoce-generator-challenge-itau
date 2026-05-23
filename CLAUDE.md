# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project context

This is the **Desafio Nota Fiscal** — a Brazilian "nota fiscal" (invoice) generator delivered as a coding challenge. The codebase ships with **intentional defects** (state bugs, performance traps, broken tests, mixed responsibilities) that are the work to be done. Treat existing bad patterns as the problem statement, not a style to follow.

Read `README.md` (in Portuguese — it's the challenge brief) and `docs/business-rules.md` (in English — the frozen behavior contract) before making non-trivial changes.

### Hard constraints from the challenge brief

- **Do not modify the input/output JSON payload.** JSON keys remain snake_case Portuguese (`id_pedido`, `valor_total_itens`, `tipo_pessoa`, …) and JSON enum values remain Portuguese (`FISICA`, `SIMPLES_NACIONAL`, `SUDESTE`, `ENTREGA`, …). Java-side names have been translated to English; the contract is preserved via `@JsonProperty`. Sample payloads: `src/main/resources/paylods/teste-pf.json` and `teste-pj-simples.json`.
- **Do not simply delete `Thread.sleep` calls.** They simulate slow external integrations (stock, registration, delivery, finance). The challenge is to handle them properly — async, parallelism, timeouts, resilience — not erase them. In particular, `DeliveryIntegrationPort` has a 5-second sleep when `items.size() > 5`; the comment there explicitly says this represents a real upstream constraint.
- The target stack is **Java 21 + a recent Spring Boot**. The repo currently sits on Java 11 / Spring Boot 2.6.2 (`pom.xml`) — upgrading is part of the work.

## Commands

Build / test (Maven wrapper is committed; use it, not a system `mvn`):

```bash
./mvnw clean package          # full build + tests
./mvnw test                   # tests only
./mvnw spring-boot:run        # run the app (default port 8080)

# Run a single test class or method
./mvnw test -Dtest=InvoiceGeneratorServiceImplTest
./mvnw test -Dtest=TaxRateSelectionFisicaTest

# Slow characterization tests are excluded by default. Run them via the slow profile:
./mvnw test -Pslow

# Coverage report (after F-SAFETY-NET — no enforcement, measurement only):
./mvnw verify  # → target/site/jacoco/index.html
```

Exercising the API locally:

```bash
curl -X POST http://localhost:8080/api/orders/generate-invoice \
  -H 'Content-Type: application/json' \
  -d @src/main/resources/paylods/teste-pf.json
```

(Note the directory is misspelled `paylods/`, not `payloads/` — keep this in mind when referencing the sample files.)

## Architecture

Single Spring Boot module, package root `br.com.itau.invoicegenerator`. One HTTP entry point, one orchestrating service, several downstream "integration" stubs.

```
web/controller/InvoiceController          POST /api/orders/generate-invoice
        │
        ▼
service/InvoiceGeneratorService  (interface)
service/impl/InvoiceGeneratorServiceImpl  ← orchestrator; contains all branching
        │
        ├─ service/ProductTaxRateCalculator     tax-rate application per item
        │
        └─ after building Invoice, calls in sequence:
              StockService          (sleep 380ms)
              RegistrationService   (sleep 500ms)
              DeliveryService → port/out/DeliveryIntegrationPort  (sleep 150 + 200ms; +5s if items > 5)
              FinanceService        (sleep 250ms)
```

For the exact tax brackets, freight multipliers, side-effect ordering, and known defects, see **`docs/business-rules.md`** — it is the canonical reference and should be updated whenever behavior changes.

### Key shapes to know about the current (broken) implementation

The orchestrator and its collaborators carry the bugs the challenge asks you to fix. Be aware of them so changes don't preserve the defect:

- **`ProductTaxRateCalculator.invoiceItemList` is `static`**: items accumulate across requests. This is the "primeira execução funciona, seguintes acumulam" bug from the README. Any refactor must make per-request state truly per-request.
- **Downstream services are instantiated with `new` inside the orchestrator** (`new StockService()`, `new RegistrationService()`, …) rather than injected. They are not Spring beans. Converting them to beans (and likely running them async/parallel) is part of the cleanup.
- **`InvoiceGeneratorServiceImpl` is one giant `if/else` tree** mixing person-type branching, tax-regime branching, freight-region branching, and side-effect orchestration. Splitting these responsibilities (strategy per `PersonType` / `CompanyTaxRegime`, freight calculator per `Region`, etc.) is expected.
- **Freight uses `double` and falls through to `0.0` when `Region` is null** (no ENTREGA/COBRANCA_ENTREGA address found) — this is a likely source of the "inconsistências nos valores" the README mentions. Money math should also probably move off `double` and onto `BigDecimal`.
- **The existing tests in `InvoiceGeneratorServiceImplTest` are misleading**: they declare `@Mock ProductTaxRateCalculator` and `@InjectMocks InvoiceGeneratorServiceImpl`, but the SUT does `new ProductTaxRateCalculator()` internally, so the mock is never used. Tests pass today only because they exercise the real calculator. Don't trust them as a safety net.
- **`JURIDICA` + `taxRegime = OUTROS`/null** falls through every branch and produces an invoice with an empty `items` list — see business-rules §3.6 and §6.2.

### Domain model

`model/` contains plain Lombok DTOs (`Order`, `Recipient`, `Address`, `Item`, `Invoice`, `InvoiceItem`, `Document`) and enums driving the branching logic: `PersonType` (FISICA/JURIDICA), `CompanyTaxRegime` (SIMPLES_NACIONAL/LUCRO_REAL/LUCRO_PRESUMIDO/OUTROS), `Region` (NORTE/NORDESTE/CENTRO_OESTE/SUDESTE/SUL), `AddressPurpose`, `DocumentType`. Enum *values* stay in Portuguese because they're part of the JSON payload contract; the enclosing class names are English. See `docs/business-rules.md` §7 for the glossary.

JSON ↔ Java mapping uses `@JsonProperty` with snake_case Portuguese keys; Java fields are camelCase English.

The `port/out/` package hints at a hexagonal-architecture direction — only `DeliveryIntegrationPort` exists today, but new external integrations should follow that pattern rather than being inlined into services.
