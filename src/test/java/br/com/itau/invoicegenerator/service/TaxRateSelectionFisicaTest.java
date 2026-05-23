package br.com.itau.invoicegenerator.service;

import static br.com.itau.invoicegenerator.testsupport.MoneyAssertions.assertBigDecimalEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.testsupport.Orders;
import br.com.itau.invoicegenerator.testsupport.RecordingTaxRateCalculator;
import br.com.itau.invoicegenerator.testsupport.TestUseCases;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SAFETY-01..05 — FISICA tax-rate selection by totalItemsValue bracket. The calculator is mocked;
 * only the *rate argument* is asserted.
 */
class TaxRateSelectionFisicaTest {

  @ParameterizedTest(name = "FISICA totalItemsValue={0} → rate={1}")
  @CsvSource({
    // below 500 → 0%
    "0.0, 0.0",
    "499.99, 0.0",
    // [500, 2000] → 12%
    "500.0, 0.12",
    "1000.0, 0.12",
    "2000.0, 0.12",
    // (2000, 3500] → 15%
    "2000.01, 0.15",
    "3000.0, 0.15",
    "3500.0, 0.15",
    // > 3500 → 17%
    "3500.01, 0.17",
    "10000.0, 0.17"
  })
  void selectsCorrectRateForFisica(double totalItemsValue, double expectedRate) {
    RecordingTaxRateCalculator calculator = new RecordingTaxRateCalculator();

    Order order = Orders.fisica(totalItemsValue);
    GenerateInvoiceUseCase service = TestUseCases.generateInvoiceUseCase(calculator);

    Invoice ignored = service.generateInvoice(order);

    assertSame(order.getItems(), calculator.lastItems());
    assertBigDecimalEquals(expectedRate, calculator.lastRate());
  }
}
