# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context

This is the **Desafio Nota Fiscal** — a Brazilian invoice generator coding challenge. The codebase intentionally contains defects that are being addressed through spec-driven tasks: safety net, Java/Spring upgrade, Clean Architecture, functional fixes, resilience/observability, and AWS architecture.

Read `README.md` and `docs/business-rules.md` before changing behavior. `docs/business-rules.md` is the frozen contract for tax brackets, freight, side effects, and known defects.

## Hard Constraints

- **Do not modify the input/output JSON payload.** JSON keys remain snake_case Portuguese (`id_pedido`, `valor_total_itens`, `tipo_pessoa`, ...), and enum values remain Portuguese (`FISICA`, `SIMPLES_NACIONAL`, `SUDESTE`, `ENTREGA`, ...). The JSON contract is isolated in `adapter/web/dto`.
- **Do not simply delete `Thread.sleep` calls.** They simulate slow external integrations. Future work should handle them with async processing, queues, timeouts, retries, and resilience.
- The active stack is **Java 21 + Spring Boot 3.5.14**. Use the default JDK 21 shell; no `JAVA_HOME` override is required.

## Commands

```bash
./mvnw clean package          # full build + tests
./mvnw test                   # fast tests only
./mvnw verify                 # tests + Spotless + Checkstyle + JaCoCo
./mvnw spring-boot:run        # run the app on port 8080

./mvnw test -Dtest=GenerateInvoiceInteractorTest
./mvnw test -Dtest=TaxRateSelectionFisicaTest

./mvnw test -Pslow            # slow characterization tests
./mvnw spotless:apply         # format Java sources
```

Exercising the API locally:

```bash
curl -X POST http://localhost:8080/api/orders/generate-invoice \
  -H 'Content-Type: application/json' \
  -d @src/main/resources/paylods/teste-pf.json
```

The sample directory is intentionally misspelled `paylods/`; keep references consistent until C-7 is handled.

## Architecture

Single Spring Boot module, package root `br.com.itau.invoicegenerator`, organized as Clean Architecture.

```
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
adapter/integration/* implementations
```

Layer rules:

- `domain/` and `application/` must not import Spring or Jackson.
- JSON DTOs and `@JsonProperty` live in `adapter/web/dto`.
- Spring bean composition lives in `adapter/config/ApplicationBeanConfig`.
- Simulated downstream systems live under `adapter/integration/{stock,registration,delivery,finance}`.

## Defect Status

F-DEFECTS-FUNCTIONAL resolved the first correctness batch:

- `LegacyProductTaxRateCalculator` is stateless per call (C-1 fixed).
- JURIDICA + `taxRegime = OUTROS`/null rejects with HTTP 400 (C-2 fixed).
- Missing delivery address or delivery address with `region=null` rejects with HTTP 400 (C-3 fixed).
- Money uses `BigDecimal`; calculated tax/freight round to scale 2 with `HALF_EVEN` (C-4 fixed).
- There is no fire-and-forget implementation today. The use case calls stock, registration, delivery, and finance ports synchronously. For production async work, prefer durable queue/outbox over detached threads or untracked `CompletableFuture.runAsync`.
- Delivery still adds 5 seconds when invoice item count is greater than 5 (C-6 still open; next feature is F-DEFECTS-PERFORMANCE).

## Testing Notes

The main safety net is 56 fast tests plus the slow profile on demand:

- `./mvnw test`: fast suite, excludes `@Tag("slow")`.
- `./mvnw test -Pslow`: slow delivery characterization.
- `./mvnw verify`: full pre-commit gate.

No current tests use Mockito; it is excluded from the test starter to keep Spring tests runnable in restricted JVM environments.
