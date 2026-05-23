# TaxRateTable

## Purpose

`TaxRateTable` decides which tax rate should be used for an order.

It does not calculate the final item tax. It only answers questions such as:

- individual customer with a total of 400 reais: rate `0.0`
- individual customer with a total of 1000 reais: rate `0.12`
- company under Simples Nacional with a total of 6000 reais: rate `0.19`
- company with tax regime `OUTROS`: reject the request

The `TaxRateCalculator` uses this rate afterward to calculate each invoice item's tax value.

## Why it returns `Optional<BigDecimal>`

The main method returns `Optional<BigDecimal>`:

```java
public Optional<BigDecimal> findRate(Order order)
```

The rate itself is a `BigDecimal` because it participates in money calculation. Rates are created from strings, such as `TaxRate.of("0.19")`, to avoid binary floating-point artifacts.

Invalid input is no longer represented as `Optional.empty()`. It is rejected with `InvalidInvoiceOrderException`:

| Case | Error code |
| --- | --- |
| `personType = null` | `INVALID_PERSON_TYPE` |
| `personType = JURIDICA`, `taxRegime = null` | `INVALID_TAX_REGIME` |
| `personType = JURIDICA`, `taxRegime = OUTROS` | `UNSUPPORTED_TAX_REGIME` |

The web adapter maps those exceptions to HTTP 400 with JSON fields `codigo` and `mensagem`.

## How the main selection works

First, the code reads the recipient and the total item value:

```java
Recipient recipient = order.getRecipient();
BigDecimal totalItemsValue = order.getTotalItemsValue();
```

Then it checks the person type:

```java
return switch (recipient.getPersonType()) {
  case FISICA -> Optional.of(rateForFisica(totalItemsValue));
  case JURIDICA -> rateForJuridica(recipient.getTaxRegime(), totalItemsValue);
};
```

In other words:

- when it is `FISICA`, use the individual-person table
- when it is `JURIDICA`, also inspect the tax regime

## Legal entity

For legal entities, the code uses another `switch`:

```java
return switch (taxRegime) {
  case SIMPLES_NACIONAL -> Optional.of(rateForSimplesNacional(totalItemsValue));
  case LUCRO_REAL -> Optional.of(rateForLucroReal(totalItemsValue));
  case LUCRO_PRESUMIDO -> Optional.of(rateForLucroPresumido(totalItemsValue));
  case OUTROS -> throw new InvalidInvoiceOrderException(...);
};
```

In other words:

- `SIMPLES_NACIONAL` uses the Simples Nacional table
- `LUCRO_REAL` uses the Lucro Real table
- `LUCRO_PRESUMIDO` uses the Lucro Presumido table
- `OUTROS` is not supported for invoice generation and is rejected

## What brackets are

A `TaxRateBracket` represents a value range.

Example:

```java
TaxRateBracket.lessThan(500, "0")
```

It means:

- if the value is less than `500`
- the rate is `0`

Another example:

```java
TaxRateBracket.upTo(2000, "0.12")
```

It means:

- if the value is less than or equal to `2000`
- the rate is `0.12`

## Example with individual customers

The individual-person table is:

```java
private static final TaxRateBracket[] FISICA_BRACKETS = {
  TaxRateBracket.lessThan(500, "0"),
  TaxRateBracket.upTo(2000, "0.12"),
  TaxRateBracket.upTo(3500, "0.15")
};
```

And the fallback is:

```java
return rateFor(totalItemsValue, FISICA_BRACKETS, TaxRate.of("0.17"));
```

So the full reading is:

| Total value | Rate |
| --- | --- |
| less than 500 | `0.0` |
| from 500 up to 2000 | `0.12` |
| above 2000 up to 3500 | `0.15` |
| above 3500 | `0.17` |

The `fallbackRate` is the final range. It is used when none of the previous ranges match.

## How `rateFor` walks the table

The method:

```java
private BigDecimal rateFor(
    BigDecimal totalItemsValue, TaxRateBracket[] brackets, BigDecimal fallbackRate)
```

does this:

1. walk through the brackets in order
2. test whether the value belongs to the current bracket
3. if it matches, return that bracket's rate
4. if no bracket matches, return the fallback

Example with `totalItemsValue = 1000` for an individual customer:

1. `1000 < 500`? No.
2. `1000 <= 2000`? Yes.
3. Return `0.12`.

Example with `totalItemsValue = 5000` for an individual customer:

1. `5000 < 500`? No.
2. `5000 <= 2000`? No.
3. `5000 <= 3500`? No.
4. Return fallback `0.17`.

## Why it is structured this way

Before this change, the rule was a large sequence of `if` / `else if` checks.

Now the ranges are declared as data:

```java
TaxRateBracket.upTo(5000, "0.13")
```

This makes it clearer:

- which limits exist
- which rate belongs to each limit
- which rate is the final fallback

It also reduces repetition, because the `rateFor` method serves all tables.

## Money behavior

`TaxRateTable` only returns the rate. The final money calculation happens in `LegacyProductTaxRateCalculator`:

```java
itemTaxValue = Money.rounded(item.getUnitPrice().multiply(taxRate));
```

`Money.rounded` applies scale 2 with `RoundingMode.HALF_EVEN`. This fixed C-4, where the domain previously used primitive `double` for money.
