package br.com.itau.invoicegenerator.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

  public static final int SCALE = 2;
  public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

  private Money() {}

  public static BigDecimal of(double value) {
    return BigDecimal.valueOf(value);
  }

  public static BigDecimal rounded(BigDecimal value) {
    return value.setScale(SCALE, ROUNDING_MODE);
  }
}
