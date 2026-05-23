package br.com.itau.invoicegenerator.service.characterization;

import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.exception.InvalidInvoiceOrderException;
import br.com.itau.invoicegenerator.domain.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.testsupport.Orders;
import br.com.itau.invoicegenerator.testsupport.RecordingTaxRateCalculator;
import br.com.itau.invoicegenerator.testsupport.TestUseCases;
import org.junit.jupiter.api.Test;

/**
 * C-2 regression tests: unsupported or missing tax regime for JURIDICA must be rejected instead of
 * generating an inconsistent invoice with empty items.
 */
class UnhandledTaxRegimeCharacterizationTest {

  @Test
  void juridicaWithOutrosTaxRegimeIsRejected_C2Fixed() {
    RecordingTaxRateCalculator calculator = new RecordingTaxRateCalculator();
    GenerateInvoiceUseCase service = TestUseCases.generateInvoiceUseCase(calculator);
    Order order = Orders.juridica(1000.0, CompanyTaxRegime.OUTROS);

    assertThrows(InvalidInvoiceOrderException.class, () -> service.generateInvoice(order));
  }

  @Test
  void juridicaWithNullTaxRegimeIsRejected_C2Fixed() {
    RecordingTaxRateCalculator calculator = new RecordingTaxRateCalculator();
    GenerateInvoiceUseCase service = TestUseCases.generateInvoiceUseCase(calculator);
    Order order = Orders.juridica(1000.0, null);

    assertThrows(InvalidInvoiceOrderException.class, () -> service.generateInvoice(order));
  }
}
