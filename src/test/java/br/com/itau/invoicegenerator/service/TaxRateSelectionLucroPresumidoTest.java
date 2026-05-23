package br.com.itau.invoicegenerator.service;

import static br.com.itau.invoicegenerator.testsupport.MoneyAssertions.assertBigDecimalEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.testsupport.Orders;
import br.com.itau.invoicegenerator.testsupport.RecordingTaxRateCalculator;
import br.com.itau.invoicegenerator.testsupport.TestUseCases;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SAFETY-11 — JURIDICA × LUCRO_PRESUMIDO tax-rate brackets. */
class TaxRateSelectionLucroPresumidoTest {

  @ParameterizedTest(name = "LUCRO_PRESUMIDO totalItemsValue={0} → rate={1}")
  @CsvSource({
    "0.0, 0.03",
    "999.99, 0.03",
    "1000.0, 0.09",
    "2000.0, 0.09",
    "2000.01, 0.16",
    "5000.0, 0.16",
    "5000.01, 0.20",
    "10000.0, 0.20"
  })
  void selectsCorrectRateForLucroPresumido(double totalItemsValue, double expectedRate) {
    RecordingTaxRateCalculator calculator = new RecordingTaxRateCalculator();

    Order order = Orders.juridica(totalItemsValue, CompanyTaxRegime.LUCRO_PRESUMIDO);
    GenerateInvoiceUseCase service = TestUseCases.generateInvoiceUseCase(calculator);

    Invoice ignored = service.generateInvoice(order);

    assertSame(order.getItems(), calculator.lastItems());
    assertBigDecimalEquals(expectedRate, calculator.lastRate());
  }
}
