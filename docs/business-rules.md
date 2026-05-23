# Invoice Generator — Business Rules

This document captures the *intended* behavior of the invoice generator, frozen before the refactor work begins. It is the contract any refactor must preserve: identifiers move and bugs get fixed, but the math, the side-effect set, and the JSON payload shape stay the same.

The original codebase is in Portuguese. This document uses English for prose and identifiers, but keeps Brazilian-domain terms (CPF, CNPJ, Simples Nacional, Lucro Real, Lucro Presumido, and the five Brazilian regions) where they have no equivalent in English. A glossary follows at the end.

> **Invariant the README locks down:** the JSON payload format must not change. JSON keys remain snake_case Portuguese (`id_pedido`, `valor_total_itens`, `tipo_pessoa`, `regime_tributacao`, etc.) and JSON enum values remain Portuguese (`FISICA`, `SIMPLES_NACIONAL`, `SUDESTE`, `ENTREGA`, …). Only Java-side names are translated.

---

## 1. Endpoint

| Method | Path                          | Body         | Response |
| ------ | ----------------------------- | ------------ | -------- |
| POST   | `/api/orders/generate-invoice`| `Order` JSON | `Invoice` JSON |

> *Note:* The legacy URL was `POST /api/pedido/gerarNotaFiscal`. The URL is not part of the locked payload contract, so it has been translated alongside the code. If external clients pin the old path, expose it as an alias.

The controller delegates to `InvoiceGeneratorService.generateInvoice(Order)` and returns the resulting `Invoice` with HTTP 200.

---

## 2. Inputs the rules depend on

From `Order`:

- `totalItemsValue` (`valor_total_itens`) — drives tax-rate brackets.
- `freightValue` (`valor_frete`) — base freight, adjusted by region.
- `items[]` (`itens`) — each has `itemId`, `description`, `unitPrice`, `quantity`.
- `recipient.personType` (`tipo_pessoa`) — `FISICA` or `JURIDICA`.
- `recipient.taxRegime` (`regime_tributacao`) — required when `personType = JURIDICA`. One of `SIMPLES_NACIONAL`, `LUCRO_REAL`, `LUCRO_PRESUMIDO`, `OUTROS`.
- `recipient.addresses[]` (`enderecos`) — the first address whose `purpose` (`finalidade`) is `ENTREGA` or `COBRANCA_ENTREGA` provides the `region` for the freight adjustment.

The system never reads `Order.idPedido`, `Order.date`, document numbers, or items' `quantity` for any calculation — those are pass-through fields. **In particular, `quantity` is currently ignored when computing tax (see §3) and that matches the existing behavior.**

---

## 3. Per-item tax calculation

Each `Item` in the order is converted into an `InvoiceItem` with an `itemTaxValue` (`valor_tributo_item`). The formula is:

```
itemTaxValue = unitPrice * taxRate
```

> **Note (carried-over behavior):** `quantity` is not part of the formula in the legacy code. If a refactor changes this, it changes the contract — flag it explicitly.

The `taxRate` is selected from the table below using `recipient.personType`, then `recipient.taxRegime`, then `order.totalItemsValue`:

### 3.1 `personType = FISICA`

| `totalItemsValue` range | Rate  |
| ----------------------- | ----- |
| `< 500`                 | 0%    |
| `500 ≤ x ≤ 2000`        | 12%   |
| `2000 < x ≤ 3500`       | 15%   |
| `> 3500`                | 17%   |

### 3.2 `personType = JURIDICA`, `taxRegime = SIMPLES_NACIONAL`

| `totalItemsValue` range | Rate  |
| ----------------------- | ----- |
| `< 1000`                | 3%    |
| `1000 ≤ x ≤ 2000`       | 7%    |
| `2000 < x ≤ 5000`       | 13%   |
| `> 5000`                | 19%   |

### 3.3 `personType = JURIDICA`, `taxRegime = LUCRO_REAL`

| `totalItemsValue` range | Rate  |
| ----------------------- | ----- |
| `< 1000`                | 3%    |
| `1000 ≤ x ≤ 2000`       | 9%    |
| `2000 < x ≤ 5000`       | 15%   |
| `> 5000`                | 20%   |

### 3.4 `personType = JURIDICA`, `taxRegime = LUCRO_PRESUMIDO`

| `totalItemsValue` range | Rate  |
| ----------------------- | ----- |
| `< 1000`                | 3%    |
| `1000 ≤ x ≤ 2000`       | 9%    |
| `2000 < x ≤ 5000`       | 16%   |
| `> 5000`                | 20%   |

### 3.5 Bracket edges

The legacy code uses `<` for the lowest bracket and `<=` for the upper edge of the middle brackets — so the value `2000` for `FISICA` lands in the 12% bracket, not 15%. Keep this exactly when refactoring; do not "normalize" the comparisons.

### 3.6 `OUTROS` and missing regime

The legacy implementation has no branch for `taxRegime = OUTROS` or `taxRegime = null` when `personType = JURIDICA`. In that case the invoice ends up with an **empty `items` list** (no tax computed). This is a known defect, listed in §6.

---

## 4. Freight adjustment

The base `freightValue` is multiplied by a region-specific factor. The `region` is taken from the first `Address` whose `purpose` is `ENTREGA` or `COBRANCA_ENTREGA`.

| `region`        | Multiplier |
| --------------- | ---------- |
| `NORTE`         | 1.080      |
| `NORDESTE`      | 1.085      |
| `CENTRO_OESTE`  | 1.070      |
| `SUDESTE`       | 1.048      |
| `SUL`           | 1.060      |

If no matching address exists (or the matched address has no `region`), the legacy code produces `adjustedFreight = 0`. This is a known defect, listed in §6.

---

## 5. Side-effect pipeline

After the `Invoice` aggregate is built (id = random UUID, date = server `now`, items, adjusted freight, recipient), the service synchronously fires four downstream calls in this order:

1. **Stock** — `StockService.sendInvoiceForStockDeduction(invoice)` (legacy sleep: 380 ms)
2. **Registration** — `RegistrationService.registerInvoice(invoice)` (legacy sleep: 500 ms)
3. **Delivery** — `DeliveryService.scheduleDelivery(invoice)` → `DeliveryIntegrationPort.createDeliverySchedule(invoice)` (legacy sleep: 150 ms + 200 ms; **plus 5000 ms when `items.size() > 5`**)
4. **Finance** — `FinanceService.sendInvoiceToAccountsReceivable(invoice)` (legacy sleep: 250 ms)

The sleeps simulate slow external systems. They are part of the challenge premise — they must not be deleted, but they may be handled with async/parallel dispatch, timeouts, or resilience patterns. The order in which the four side effects fire is not load-bearing for the response, but each one must still happen for every invoice.

---

## 6. Known defects (intentional — to be fixed during the refactor, not now)

These are documented so a refactor can identify them as bugs rather than mistakenly preserving them as "rules":

1. **Cross-request accumulation.** `ProductTaxRateCalculator` (legacy `CalculadoraAliquotaProduto`) keeps a `static` list of `InvoiceItem`s, so each request's tax items are appended to the previous request's. The correct behavior: each request returns only its own items.
2. **Stale tax-rate fallback.** When `personType = JURIDICA` with `taxRegime = OUTROS` (or null), no rate is applied and `items` is empty. The correct behavior should be specified by the business (likely: reject the request, or apply a defined default).
3. **Missing-region freight.** When no delivery/billing address has a region, `adjustedFreight = 0`. The correct behavior should be defined (likely: error, or pass through `freightValue` unchanged).
4. **`double` for money.** All monetary fields are `double`. This is acceptable as input/output JSON, but tax math should move to `BigDecimal` with explicit rounding to avoid the inconsistencies external systems reported.
5. **Synchronous + slow.** The four side effects run sequentially on the request thread; the +5s sleep on orders with more than 5 items makes the request budget unbounded.

---

## 7. Glossary — Brazilian terms kept untranslated

| Term                 | Meaning                                                                                  |
| -------------------- | ---------------------------------------------------------------------------------------- |
| `FISICA`             | "Pessoa física" — an individual (natural person).                                       |
| `JURIDICA`           | "Pessoa jurídica" — a company / legal entity.                                            |
| `CPF`                | National tax ID issued to individuals.                                                   |
| `CNPJ`               | National tax ID issued to companies.                                                     |
| `SIMPLES_NACIONAL`   | Simplified unified tax regime for small businesses.                                      |
| `LUCRO_REAL`         | "Real profit" corporate tax regime — taxes based on actual profit.                       |
| `LUCRO_PRESUMIDO`    | "Presumed profit" corporate tax regime — taxes based on a presumed margin.               |
| `OUTROS`             | "Other" — catch-all bucket for tax regime / address purpose.                             |
| `NORTE`/`NORDESTE`/`CENTRO_OESTE`/`SUDESTE`/`SUL` | The five Brazilian macro-regions.                                            |
| `ENTREGA`            | Address purpose: delivery.                                                               |
| `COBRANCA`           | Address purpose: billing.                                                                |
| `COBRANCA_ENTREGA`   | Address purpose: combined billing and delivery.                                          |

These values are part of the JSON payload contract and therefore stay untranslated in both the JSON schema and the Java enum constants. Their *enclosing class names* in Java have been translated (e.g. `TipoPessoa` → `PersonType`).