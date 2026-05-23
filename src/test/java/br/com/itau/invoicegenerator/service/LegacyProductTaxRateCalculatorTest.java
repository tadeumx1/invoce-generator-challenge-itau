package br.com.itau.invoicegenerator.service;

import static br.com.itau.invoicegenerator.testsupport.MoneyAssertions.assertBigDecimalEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import br.com.itau.invoicegenerator.domain.model.InvoiceItem;
import br.com.itau.invoicegenerator.domain.model.Item;
import br.com.itau.invoicegenerator.domain.model.Money;
import br.com.itau.invoicegenerator.domain.model.TaxRate;
import br.com.itau.invoicegenerator.domain.service.LegacyProductTaxRateCalculator;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyProductTaxRateCalculatorTest {

  @Test
  void appliesRateToUnitPriceAndCopiesItemFields() {
    LegacyProductTaxRateCalculator calculator = new LegacyProductTaxRateCalculator();
    Item item = new Item("item-1", "Teclado", Money.of(100.0), 3);

    List<InvoiceItem> result = calculator.calculateTax(List.of(item), TaxRate.of("0.10"));

    assertEquals(1, result.size());
    InvoiceItem out = result.get(0);
    assertEquals("item-1", out.getItemId());
    assertEquals("Teclado", out.getDescription());
    assertBigDecimalEquals(100.0, out.getUnitPrice());
    assertEquals(3, out.getQuantity());
    assertBigDecimalEquals(10.0, out.getItemTaxValue());
  }

  @Test
  void returnsOneInvoiceItemPerInputItemInOrder() {
    LegacyProductTaxRateCalculator calculator = new LegacyProductTaxRateCalculator();
    Item a = new Item("a", "A", Money.of(50.0), 1);
    Item b = new Item("b", "B", Money.of(200.0), 2);

    List<InvoiceItem> result = calculator.calculateTax(List.of(a, b), TaxRate.of("0.05"));

    assertEquals(2, result.size());
    assertEquals("a", result.get(0).getItemId());
    assertBigDecimalEquals(2.5, result.get(0).getItemTaxValue());
    assertEquals("b", result.get(1).getItemId());
    assertBigDecimalEquals(10.0, result.get(1).getItemTaxValue());
  }

  /**
   * C-1 regression test: two consecutive calls on the same calculator instance must not leak items
   * from the first call into the second.
   */
  @Test
  void consecutiveCallsDoNotAccumulateOnSameInstance_C1Fixed() {
    LegacyProductTaxRateCalculator calculator = new LegacyProductTaxRateCalculator();
    Item first = new Item("first", "First", Money.of(100.0), 1);
    Item second = new Item("second", "Second", Money.of(200.0), 1);

    calculator.calculateTax(List.of(first), TaxRate.of("0.10"));
    List<InvoiceItem> result2 = calculator.calculateTax(List.of(second), TaxRate.of("0.10"));

    assertEquals(
        1, result2.size(), "C-1 fixed: second call must return only the second request's item");

    // Sanity: a fresh calculator instance is isolated.
    LegacyProductTaxRateCalculator fresh = new LegacyProductTaxRateCalculator();
    List<InvoiceItem> isolated = fresh.calculateTax(List.of(first), TaxRate.of("0.10"));
    assertEquals(1, isolated.size());
    assertNotSame(result2, isolated);
  }
}
