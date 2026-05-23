# Code Conventions

These are conventions **observed in the current code** (post-rename to English). They are the *de facto* style — not necessarily the target. The Clean Architecture refactor (F-CLEAN) is expected to introduce additional layer-specific conventions.

## Naming Conventions

**Files:** PascalCase, one public type per file, matches type name. Examples: `Order.java`, `InvoiceGeneratorServiceImpl.java`, `DeliveryIntegrationPort.java`.

**Packages:** lowercase, dotted, starting at `br.com.itau.invoicegenerator`. Subpackages by layer: `model`, `service`, `service.impl`, `port.out`, `web.controller`.

**Classes:** PascalCase. Interfaces use plain noun (`InvoiceGeneratorService`), implementations append `Impl` (`InvoiceGeneratorServiceImpl`).

**Methods:** camelCase, verb-first. Examples: `generateInvoice`, `calculateTax`, `sendInvoiceForStockDeduction`.

**Fields / local variables:** camelCase. Examples: `totalItemsValue`, `adjustedFreightValue`, `taxRate`.

**Constants:** none currently in code. Enum constants are SCREAMING_SNAKE_CASE and remain in Portuguese (`FISICA`, `SIMPLES_NACIONAL`, `COBRANCA_ENTREGA`) because they double as the JSON contract values.

## Code Organization

**Import order:** No enforced order — files mix project imports, third-party (Lombok, Spring, Jackson), and JDK in arbitrary order. The Java 21 + Spring 3 upgrade is a natural moment to introduce an import-order policy via google-java-format or spotless.

**File structure:** typical Java single-class-per-file. Lombok annotations stacked at the top of every model class, then `@JsonProperty` fields. Example: `Recipient.java`.

## Type Safety / Documentation

**Approach:** Standard Java types throughout; no `var`, no records. All money handled as primitive `double` (known defect — see `CONCERNS.md` C-4). Collections via `java.util.List`, ArrayList for mutables.

**Nullability:** No annotations (`@Nullable`, `@NonNull`, or `Optional<T>` in fields). Nulls are pervasive (e.g., `Region` can come back null from the address-search and silently zero out freight — see `CONCERNS.md` C-3).

## Error Handling

**Pattern:** `Thread.sleep` calls are wrapped in `try { ... } catch (InterruptedException e) { throw new RuntimeException(e); }` — losing the interrupt flag and not preserving the cause's typed information. There is no application-level exception type; nothing translates domain errors to HTTP responses. The controller has no `@ExceptionHandler` / `@ControllerAdvice`.

**Example:** `StockService.java:8-12`.

This is one of the things the refactor will tighten (proper error model, `Thread.currentThread().interrupt()`, typed exceptions, global handler).

## Comments / Documentation

**Style:** sparse. Existing comments are short, single-line, and English (Portuguese comments were translated during the English rename). Inline comments call out known defects (e.g., `ProductTaxRateCalculator.java:11-12` references `docs/business-rules.md` §6.1).

**Javadoc:** none.

## Tests

**Naming:** `should<Behavior>For<Context>With<Condition>` — verbose, expressive. Example: `shouldGenerateInvoiceForPersonTypeJuridicaWithLucroPresumidoAndTotalItemsValueGreaterThan5000`.

**Layout:** flat — one test class per production class, sitting at the test-tree mirror of the package. Today only `InvoiceGeneratorServiceImplTest` and `InvoiceGeneratorApplicationTests` exist.

**Mock framework:** Mockito with annotation-driven setup (`@Mock`, `@InjectMocks`, `MockitoAnnotations.openMocks`). See `TESTING.md` for the known anti-pattern in current tests.
