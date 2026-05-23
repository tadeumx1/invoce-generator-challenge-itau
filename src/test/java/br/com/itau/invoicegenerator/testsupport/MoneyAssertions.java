package br.com.itau.invoicegenerator.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

public final class MoneyAssertions {

  private MoneyAssertions() {}

  public static void assertBigDecimalEquals(double expected, BigDecimal actual) {
    assertEquals(0, BigDecimal.valueOf(expected).compareTo(actual));
  }

  public static void assertBigDecimalEquals(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual));
  }
}
