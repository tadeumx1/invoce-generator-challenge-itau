package br.com.itau.invoicegenerator.domain.service;

import br.com.itau.invoicegenerator.domain.exception.InvalidInvoiceOrderException;
import br.com.itau.invoicegenerator.domain.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.domain.model.Recipient;
import br.com.itau.invoicegenerator.domain.model.TaxRate;
import java.math.BigDecimal;
import java.util.Optional;

public class TaxRateTable {

  private static final TaxRateBracket[] FISICA_BRACKETS = {
    TaxRateBracket.lessThan(500, "0"),
    TaxRateBracket.upTo(2000, "0.12"),
    TaxRateBracket.upTo(3500, "0.15")
  };

  private static final TaxRateBracket[] SIMPLES_NACIONAL_BRACKETS = {
    TaxRateBracket.lessThan(1000, "0.03"),
    TaxRateBracket.upTo(2000, "0.07"),
    TaxRateBracket.upTo(5000, "0.13")
  };

  private static final TaxRateBracket[] LUCRO_REAL_BRACKETS = {
    TaxRateBracket.lessThan(1000, "0.03"),
    TaxRateBracket.upTo(2000, "0.09"),
    TaxRateBracket.upTo(5000, "0.15")
  };

  private static final TaxRateBracket[] LUCRO_PRESUMIDO_BRACKETS = {
    TaxRateBracket.lessThan(1000, "0.03"),
    TaxRateBracket.upTo(2000, "0.09"),
    TaxRateBracket.upTo(5000, "0.16")
  };

  public Optional<BigDecimal> findRate(Order order) {
    Recipient recipient = order.getRecipient();
    BigDecimal totalItemsValue = order.getTotalItemsValue();

    if (recipient.getPersonType() == null) {
      throw invalid("INVALID_PERSON_TYPE", "Recipient person type is required.");
    }

    return switch (recipient.getPersonType()) {
      case FISICA -> Optional.of(rateForFisica(totalItemsValue));
      case JURIDICA -> rateForJuridica(recipient.getTaxRegime(), totalItemsValue);
    };
  }

  private BigDecimal rateForFisica(BigDecimal totalItemsValue) {
    return rateFor(totalItemsValue, FISICA_BRACKETS, TaxRate.of("0.17"));
  }

  private Optional<BigDecimal> rateForJuridica(
      CompanyTaxRegime taxRegime, BigDecimal totalItemsValue) {
    if (taxRegime == null) {
      throw invalid("INVALID_TAX_REGIME", "Tax regime is required for juridica recipients.");
    }

    return switch (taxRegime) {
      case SIMPLES_NACIONAL -> Optional.of(rateForSimplesNacional(totalItemsValue));
      case LUCRO_REAL -> Optional.of(rateForLucroReal(totalItemsValue));
      case LUCRO_PRESUMIDO -> Optional.of(rateForLucroPresumido(totalItemsValue));
      case OUTROS ->
          throw invalid(
              "UNSUPPORTED_TAX_REGIME",
              "Tax regime OUTROS is not supported for invoice generation.");
    };
  }

  private BigDecimal rateForSimplesNacional(BigDecimal totalItemsValue) {
    return rateFor(totalItemsValue, SIMPLES_NACIONAL_BRACKETS, TaxRate.of("0.19"));
  }

  private BigDecimal rateForLucroReal(BigDecimal totalItemsValue) {
    return rateFor(totalItemsValue, LUCRO_REAL_BRACKETS, TaxRate.of("0.20"));
  }

  private BigDecimal rateForLucroPresumido(BigDecimal totalItemsValue) {
    return rateFor(totalItemsValue, LUCRO_PRESUMIDO_BRACKETS, TaxRate.of("0.20"));
  }

  private BigDecimal rateFor(
      BigDecimal totalItemsValue, TaxRateBracket[] brackets, BigDecimal fallbackRate) {
    for (TaxRateBracket bracket : brackets) {
      if (bracket.matches(totalItemsValue)) {
        return bracket.rate();
      }
    }
    return fallbackRate;
  }

  private InvalidInvoiceOrderException invalid(String code, String message) {
    return new InvalidInvoiceOrderException(code, message);
  }

  private record TaxRateBracket(BigDecimal upperBound, boolean inclusive, BigDecimal rate) {

    static TaxRateBracket lessThan(double upperBound, String rate) {
      return new TaxRateBracket(BigDecimal.valueOf(upperBound), false, TaxRate.of(rate));
    }

    static TaxRateBracket upTo(double upperBound, String rate) {
      return new TaxRateBracket(BigDecimal.valueOf(upperBound), true, TaxRate.of(rate));
    }

    boolean matches(BigDecimal value) {
      if (inclusive) {
        return value.compareTo(upperBound) <= 0;
      }
      return value.compareTo(upperBound) < 0;
    }
  }
}
