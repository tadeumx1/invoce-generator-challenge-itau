package br.com.itau.invoicegenerator.domain.model;

import java.math.BigDecimal;

public final class TaxRate {

  private TaxRate() {}

  public static BigDecimal of(String value) {
    return new BigDecimal(value);
  }
}
