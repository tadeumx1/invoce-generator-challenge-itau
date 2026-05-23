package br.com.itau.invoicegenerator.service.characterization;

import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.exception.InvalidInvoiceOrderException;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.domain.model.Region;
import br.com.itau.invoicegenerator.testsupport.Addresses;
import br.com.itau.invoicegenerator.testsupport.Orders;
import br.com.itau.invoicegenerator.testsupport.RecordingTaxRateCalculator;
import br.com.itau.invoicegenerator.testsupport.TestUseCases;
import org.junit.jupiter.api.Test;

/**
 * C-3 regression tests: missing/null delivery region must be rejected instead of silently producing
 * freight = 0.
 */
class MissingRegionFreightCharacterizationTest {

  @Test
  void rejectsOrderWhenNoAddressHasDeliveryPurpose_C3Fixed() {
    // Recipient only has COBRANCA addresses; no ENTREGA / COBRANCA_ENTREGA → region lookup yields
    // null.
    Order order = Orders.fisica(0, Addresses.cobranca(Region.SUDESTE));
    RecordingTaxRateCalculator calculator = new RecordingTaxRateCalculator();
    GenerateInvoiceUseCase service = TestUseCases.generateInvoiceUseCase(calculator);

    assertThrows(InvalidInvoiceOrderException.class, () -> service.generateInvoice(order));
  }

  @Test
  void rejectsOrderWhenDeliveryAddressHasNullRegion_C3Fixed() {
    Order order = Orders.fisica(0, Addresses.entregaWithNullRegion());
    RecordingTaxRateCalculator calculator = new RecordingTaxRateCalculator();
    GenerateInvoiceUseCase service = TestUseCases.generateInvoiceUseCase(calculator);

    assertThrows(InvalidInvoiceOrderException.class, () -> service.generateInvoice(order));
  }
}
