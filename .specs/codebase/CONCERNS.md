# Concerns

Actionable warnings about the codebase. Each entry has evidence (file:line), impact, and a proposed fix. Ranked by risk × likelihood × user-visibility.

> All concerns here track to features in `ROADMAP.md`. The README itself flags most of them as challenge tasks.

---

## C-1 — Static accumulating list in `ProductTaxRateCalculator` ⛔ critical

**Evidence:** `service/ProductTaxRateCalculator.java:10` — `private static List<InvoiceItem> invoiceItemList = new ArrayList<>();`
**Impact:** Every request appends its items to a JVM-shared list and returns the *cumulative* list. The first request returns N items; the second returns N + M; the third returns N + M + K. Cross-customer data leakage in a multi-tenant scenario; obviously wrong invoices in all scenarios.
**Reproduction:** Run `InvoiceGeneratorServiceImplTest` twice in the same JVM (it's actually one JVM with two `@Test`s, and the second test sees the first test's item — `expected: <1> but was: <2>`).
**Fix:** Make the calculator stateless. Either inject as a Spring `@Component` and store no state, or pass results out as a return value of a pure function. Drop the `static` field entirely. Track under **F-DEFECTS-FUNCTIONAL** (`ROADMAP.md`).

---

## C-2 — Missing branch for `taxRegime = OUTROS` and `taxRegime = null` 🔴 high

**Evidence:** `service/impl/InvoiceGeneratorServiceImpl.java:38-86` — `if/else if` chain on `taxRegime` covers `SIMPLES_NACIONAL`, `LUCRO_REAL`, `LUCRO_PRESUMIDO`; nothing for `OUTROS` or `null`.
**Impact:** For `JURIDICA` recipients with `taxRegime = OUTROS` (a declared enum constant) or with a missing field, the `invoiceItems` list stays empty. The invoice is built with `items: []` but `totalItemsValue` from the request is preserved — an inconsistency the README mentions ("Outros sistemas relatam inconsistências nos valores da nota e no total de itens").
**Fix:** Either reject the request (400 Bad Request with a typed error) or apply a documented default. Confirm with product. Track under **F-DEFECTS-FUNCTIONAL**.

---

## C-3 — Freight is broken when no ENTREGA address has a usable region 🔴 high

**Evidence:** `service/impl/InvoiceGeneratorServiceImpl.java` — the address lookup:

```java
Region region = recipient.getAddresses().stream()
        .filter(a -> a.getPurpose() == AddressPurpose.ENTREGA
                  || a.getPurpose() == AddressPurpose.COBRANCA_ENTREGA)
        .map(Address::getRegion)
        .findFirst()
        .orElse(null);
```

This has TWO distinct buggy paths, discovered during F-SAFETY-NET execution and confirmed by tests `MissingRegionFreightCharacterizationTest`:

1. **No delivery address present** (only `COBRANCA` or no addresses match) → `findFirst()` returns `Optional.empty()` → `orElse(null)` produces `region = null` → if/else chain doesn't match → `adjustedFreightValue` stays at its initialized `0`. Silent freight=0.
2. **Delivery address present but `region = null`** → `findFirst()` is called on a `Stream<Region>` whose first element is `null`. Per Java's `Stream` spec, **`findFirst()` throws `NullPointerException`** on null elements. The request fails with HTTP 500, not silently with freight=0.

**Impact:**
- Case 1: customers can receive a zero freight charge for malformed addresses (matches the README's "inconsistências nos valores da nota").
- Case 2: any request with a delivery address missing the region returns 500 — worse than a wrong value, the request fails entirely.

**Fix:** Reject with HTTP 400 when no delivery address is present (it's required for delivery scheduling anyway) and when the delivery address has no region; or pass freight through unchanged with a logged warning. Confirm policy. Track under **F-DEFECTS-FUNCTIONAL**.

---

## C-4 — Money handled as `double` 🔴 high

**Evidence:** Every monetary field — `Order.totalItemsValue`, `Order.freightValue`, `Invoice.totalItemsValue`, `Invoice.freightValue`, `Item.unitPrice`, `InvoiceItem.itemTaxValue`, `InvoiceItem.unitPrice` — is a `double`. Arithmetic (`item.getUnitPrice() * taxRate`, `freightValue * 1.085`) compounds rounding error.
**Impact:** Inconsistencies between the values stored, returned to the client, and forwarded to downstream systems. Aligns with the README's "Outros sistemas relatam inconsistências nos valores da nota".
**Fix:** Migrate domain-side arithmetic to `BigDecimal` with explicit rounding mode (`HALF_EVEN`, 2 decimal places for BRL). Keep JSON as `Number` on the wire — Jackson can serialize `BigDecimal` to a JSON number transparently. Track under **F-DEFECTS-FUNCTIONAL**.

---

## C-5 — Tests declare mocks that never apply 🟠 medium

**Evidence:** `src/test/java/.../InvoiceGeneratorServiceImplTest.java:14-22` — `@Mock ProductTaxRateCalculator` + `@InjectMocks InvoiceGeneratorServiceImpl`. The SUT instantiates the calculator with `new ProductTaxRateCalculator()` inside `generateInvoice`, so the mock is never injected and never observed.
**Impact:** Tests look isolated but actually exercise the real calculator (including its static-list bug). Gives a misleading sense of test design. Combined with C-1, the two existing tests are order-dependent.
**Fix:** Either (a) make the calculator injectable as a constructor dependency and actually mock it, or (b) drop the misleading `@Mock` and embrace the integration nature of the test. Likely (a), since the Clean Architecture refactor (F-CLEAN) is going to inject everything anyway. Track under **F-SAFETY-NET**.

---

## C-6 — Side-effects run synchronously on the request thread 🟠 medium

**Evidence:** `service/impl/InvoiceGeneratorServiceImpl.java:127-130` — four sequential `new XxxService().yyy(invoice)` calls, each `Thread.sleep`-ing.
**Latency budget:** 380 + 500 + 150 + 200 + 250 = **1480 ms** for any order; **+5000 ms** more when `items.size() > 5`.
**Impact:** Tail latency is unbounded by upstream slowness. The request thread is held throughout. No timeouts; no circuit breakers; no retries; no observability into which leg was slow.
**Fix:** Move non-critical side effects (stock, finance, optionally delivery) to async dispatch (outbox + SQS/queue worker). Wrap remaining synchronous calls with timeouts and circuit breakers (Resilience4j). Track under **F-RESILIENCE** for the runtime patterns and **F-DEFECTS-PERFORMANCE** for the +5s on >5 items.

---

## C-7 — Misspelled resource directory `paylods/` 🟡 low (cosmetic)

**Evidence:** `src/main/resources/paylods/teste-pf.json`, `teste-pj-simples.json`.
**Impact:** Confusion when looking for sample payloads; misleading file path in docs.
**Fix:** Rename to `payloads/`. Trivial. Track as a deferred idea or sweep along with F-UPGRADE.

---

## C-8 — `InterruptedException` discards interrupt flag 🟡 low

**Evidence:** Every `Thread.sleep` site — `StockService.java:11`, `RegistrationService.java:11`, `FinanceService.java:11`, `DeliveryService.java:13`, `DeliveryIntegrationPort.java:17`.
**Pattern:** `catch (InterruptedException e) { throw new RuntimeException(e); }`
**Impact:** Lost interrupt flag; downstream code in pools (executors, schedulers) can't shut down cleanly.
**Fix:** `Thread.currentThread().interrupt();` before rethrowing, and rethrow as a typed domain exception (or convert when moving to non-blocking async). Track under **F-CLEAN** since adapters get rewritten there anyway.

---

## C-9 — Missing test coverage ⚠️ structural

**Evidence:** Two tests total, both flawed (see C-5). No tests for:
- The `ProductTaxRateCalculator` itself
- `JURIDICA` × `SIMPLES_NACIONAL` and `× LUCRO_REAL` branches
- All four bracket boundaries (e.g., the `500 / 2000 / 3500` edges)
- Freight multipliers per region (5 regions × 0 tests)
- The OUTROS / null fallthrough behavior (C-2)
- The missing-region freight = 0 behavior (C-3)
- The 5-second delivery sleep on >5 items (C-6)
- The HTTP layer (`InvoiceController`)
**Impact:** No safety net for the refactor. Almost any change can silently break the contract documented in `docs/business-rules.md`.
**Fix:** Build a real test suite *before* refactoring (the user-confirmed sequence: safety net first). Track under **F-SAFETY-NET** in `ROADMAP.md`.

---

## C-10 — Pre-existing Lombok / JDK 16+ build break ✅ resolved in F-UPGRADE

**Evidence:** Spring Boot 2.6.2 → Lombok 1.18.22 → fails on JDK 16+ with `NoSuchFieldError: Class com.sun.tools.javac.tree.JCTree$JCImport does not have member field 'com.sun.tools.javac.tree.JCTree qualid'`.
**Impact:** Before F-UPGRADE, builds only succeeded under JDK 11.
**Fix:** F-UPGRADE moved the project to Java 21 + Spring Boot 3.5.14. `./mvnw verify` passes on the default JDK 21 shell.
