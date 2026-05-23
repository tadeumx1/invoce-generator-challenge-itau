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

/** SAFETY-06..09 — JURIDICA × SIMPLES_NACIONAL tax-rate brackets. */
class TaxRateSelectionSimplesNacionalTest {

  @ParameterizedTest(name = "SIMPLES_NACIONAL totalItemsValue={0} → rate={1}")
  @CsvSource({
    // < 1000 → 3%
    "0.0, 0.03",
    "999.99, 0.03",
    // [1000, 2000] → 7%
    "1000.0, 0.07",
    "2000.0, 0.07",
    // (2000, 5000] → 13%
    "2000.01, 0.13",
    "5000.0, 0.13",
    // > 5000 → 19%
    "5000.01, 0.19",
    "10000.0, 0.19"
  })
  void selectsCorrectRateForSimplesNacional(double totalItemsValue, double expectedRate) {
    RecordingTaxRateCalculator calculator = new RecordingTaxRateCalculator();

    Order order = Orders.juridica(totalItemsValue, CompanyTaxRegime.SIMPLES_NACIONAL);
    GenerateInvoiceUseCase service = TestUseCases.generateInvoiceUseCase(calculator);

    Invoice ignored = service.generateInvoice(order);

    assertSame(order.getItems(), calculator.lastItems());
    assertBigDecimalEquals(expectedRate, calculator.lastRate());
  }
}
