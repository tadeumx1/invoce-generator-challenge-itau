package br.com.itau.invoicegenerator.service.characterization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.domain.service.LegacyProductTaxRateCalculator;
import br.com.itau.invoicegenerator.testsupport.Orders;
import br.com.itau.invoicegenerator.testsupport.TestUseCases;
import org.junit.jupiter.api.Test;

/** C-1 regression test: invoice items must not leak between calls on the same service instance. */
class StaticListAccumulationCharacterizationTest {

  @Test
  void twoCallsOnSameServiceInstanceDoNotLeakItems_C1Fixed() {
    // Instantiate manually so we observe the bug deterministically without depending on the
    // Spring singleton being shared between this test and others.
    LegacyProductTaxRateCalculator calculator = new LegacyProductTaxRateCalculator();
    GenerateInvoiceUseCase service = TestUseCases.generateInvoiceUseCase(calculator);

    Order first = Orders.fisica(400);
    Order second = Orders.fisica(400);

    Invoice firstInvoice = service.generateInvoice(first);
    Invoice secondInvoice = service.generateInvoice(second);

    assertEquals(
        1,
        secondInvoice.getItems().size(),
        "C-1 fixed: second invoice contains only the second call's items");
    assertEquals(
        1,
        firstInvoice.getItems().size(),
        "C-1 fixed: first invoice's items remain isolated after the second call");
  }
}
