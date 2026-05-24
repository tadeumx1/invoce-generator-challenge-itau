# Invoice Generator — Business Rules

This document captures the intended behavior of the invoice generator after the safety-net, upgrade, Clean Architecture, and first functional-defect pass. Identifiers moved and C-1 through C-4 were fixed, but the JSON payload shape stays the same.

The original codebase is in Portuguese. This document uses English for prose and identifiers, but keeps Brazilian-domain terms (CPF, CNPJ, Simples Nacional, Lucro Real, Lucro Presumido, and the five Brazilian regions) where they have no equivalent in English. A glossary follows at the end.

> **Invariant the README locks down:** the JSON payload format must not change. JSON keys remain snake_case Portuguese (`id_pedido`, `valor_total_itens`, `tipo_pessoa`, `regime_tributacao`, etc.) and JSON enum values remain Portuguese (`FISICA`, `SIMPLES_NACIONAL`, `SUDESTE`, `ENTREGA`, …). Only Java-side names are translated.

---

## 1. Endpoint

| Method | Path                          | Body         | Response | Auth |
| ------ | ----------------------------- | ------------ | -------- | ---- |
| POST   | `/api/auth/login`             | `{username, password}` | `{access_token, token_type, expires_in, scope}` | public |
| POST   | `/api/orders/generate-invoice`| `Order` JSON | `Invoice` JSON | `Bearer` token with scope `invoice:write` |

> *Compatibility:* The main URL is `POST /api/orders/generate-invoice`. The legacy URL
> `POST /api/pedido/gerarNotaFiscal` remains available as an alias for existing clients
> (and is equally protected — see §1.1).

The controller maps the JSON DTO to a domain `Order`, delegates to `GenerateInvoiceUseCase.generateInvoice(order)`, and returns the resulting `Invoice` with HTTP 200. Domain validation failures are returned as HTTP 400 with `codigo` and `mensagem`.

### 1.1 Authentication (F-AUTH)

Mutating endpoints require an HS256 JWT issued by `POST /api/auth/login`. The token's
`scope` claim must contain `invoice:write`.

Demo users (replace before any real deployment — see
[`docs/auth-strategy.md`](auth-strategy.md)):

| Username | Password   | Scope                            |
| -------- | ---------- | -------------------------------- |
| `demo`   | `demo123`  | `invoice:write`                  |
| `admin`  | `admin123` | `invoice:write invoice:admin`    |

```bash
# 1) Log in.
TOKEN=$(curl -sS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123"}' \
  | jq -r .access_token)

# 2) Call the invoice endpoint with the token.
curl -X POST http://localhost:8080/api/orders/generate-invoice \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d @src/main/resources/payloads/teste-pf.json
```

Auth-error envelope (same `{codigo, mensagem}` shape used by the rest of the API):

| Scenario                                        | HTTP | `codigo`                |
| ----------------------------------------------- | ---- | ----------------------- |
| `POST /auth/login` missing/blank fields         | 400  | `INVALID_LOGIN_PAYLOAD` |
| `POST /auth/login` wrong password / unknown user| 401  | `INVALID_CREDENTIALS`   |
| Protected endpoint without/with malformed token | 401  | `UNAUTHORIZED`          |
| Protected endpoint with expired token           | 401  | `UNAUTHORIZED`          |
| Protected endpoint with token lacking scope     | 403  | `FORBIDDEN`             |

Public endpoints (no token required): `POST /api/auth/login`, `GET /actuator/health`,
`GET /actuator/health/**`, `GET /actuator/info`, `GET /actuator/prometheus` (the
F-OBSERVABILITY Prometheus scrape contract is preserved).

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

`taxRegime = OUTROS` is not supported for invoice generation. A `JURIDICA` recipient with `taxRegime = OUTROS` is rejected with HTTP 400 and code `UNSUPPORTED_TAX_REGIME`.

A `JURIDICA` recipient with `taxRegime = null` is also rejected with HTTP 400 and code `INVALID_TAX_REGIME`.

The previous empty-`items` fallback was defect C-2 and is now fixed.

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

If no matching delivery address exists, or if the matching delivery address has `region = null`, the request is rejected with HTTP 400 and code `INVALID_DELIVERY_REGION`.

Calculated freight is rounded to 2 decimal places with `RoundingMode.HALF_EVEN`. For example, `72 × 1.048 = 75.456` becomes `75.46`.

---

## 4.1 Money and rounding

Domain and web DTO monetary fields use `BigDecimal`. JSON still uses numeric values; field names and enum values are unchanged.

Calculated item tax and freight are rounded to scale 2 with `HALF_EVEN`:

```
itemTaxValue = round(unitPrice * taxRate, scale=2, HALF_EVEN)
adjustedFreight = round(freightValue * regionMultiplier, scale=2, HALF_EVEN)
```

---

## 5. Side-effect pipeline

After the `Invoice` aggregate is built (id = random UUID, date = server `now`, items, adjusted freight, recipient), the service synchronously fires four downstream calls in this order:

1. **Stock** — `StockService.sendInvoiceForStockDeduction(invoice)` (legacy sleep: 380 ms)
2. **Registration** — `RegistrationService.registerInvoice(invoice)` (legacy sleep: 500 ms)
3. **Delivery** — `DeliveryService.scheduleDelivery(invoice)` → `DeliveryIntegrationPort.createDeliverySchedule(invoice)` (legacy sleep: 150 ms + 200 ms; **plus 5000 ms when `items.size() > 5`**)
4. **Finance** — `FinanceService.sendInvoiceToAccountsReceivable(invoice)` (legacy sleep: 250 ms)

The sleeps simulate slow external systems. They are part of the challenge premise — they must not be deleted, but they may be handled with async/parallel dispatch, timeouts, or resilience patterns. The order in which the four side effects fire is not load-bearing for the response, but each one must still happen for every invoice.

---

## 6. Defect status

The first functional-defect pass resolved C-1 through C-4:

1. **Cross-request accumulation — resolved.** `LegacyProductTaxRateCalculator` is stateless; each request returns only its own items.
2. **Stale tax-rate fallback — resolved.** `JURIDICA + OUTROS/null` now rejects with HTTP 400 instead of returning an invoice with empty `items`.
3. **Missing-region freight — resolved.** missing delivery address and null delivery region now reject with HTTP 400 instead of producing freight `0`.
4. **`double` for money — resolved.** monetary fields now use `BigDecimal`; calculated money rounds to scale 2 with `HALF_EVEN`.
5. **Synchronous + slow — still open.** The four side effects run sequentially on the request thread; the +5s sleep on orders with more than 5 items makes the request budget unbounded. This is tracked as C-6 / F-DEFECTS-PERFORMANCE.

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
