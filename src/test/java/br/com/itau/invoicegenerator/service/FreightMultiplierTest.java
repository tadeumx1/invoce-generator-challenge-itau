package br.com.itau.invoicegenerator.service;

import static br.com.itau.invoicegenerator.testsupport.MoneyAssertions.assertBigDecimalEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.domain.model.Region;
import br.com.itau.invoicegenerator.testsupport.Addresses;
import br.com.itau.invoicegenerator.testsupport.Orders;
import br.com.itau.invoicegenerator.testsupport.RecordingTaxRateCalculator;
import br.com.itau.invoicegenerator.testsupport.TestUseCases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SAFETY-12..17 — freight multiplier per region; COBRANCA_ENTREGA also triggers the lookup. The
 * freightValue input is fixed at 100.0; expected output is the documented multiplier × 100.
 */
class FreightMultiplierTest {

  private static final double BASE_FREIGHT = 100.0;

  @ParameterizedTest(name = "region={0} → multiplier × 100 = {1}")
  @CsvSource({
    "NORTE,        108.0",
    "NORDESTE,     108.5",
    "CENTRO_OESTE, 107.0",
    "SUDESTE,      104.8",
    "SUL,          106.0"
  })
  void appliesRegionMultiplierToBaseFreightForEntregaAddress(
      String regionName, double expectedFreight) {
    Region region = Region.valueOf(regionName);
    Order order = Orders.fisica(0, Addresses.entrega(region));
    RecordingTaxRateCalculator calculator = new RecordingTaxRateCalculator();
    GenerateInvoiceUseCase service = TestUseCases.generateInvoiceUseCase(calculator);

    Invoice invoice = service.generateInvoice(order);

    assertBigDecimalEquals(expectedFreight, invoice.getFreightValue());
    assertEquals(
        expectedFreight,
        invoice.getFreightValue().doubleValue(),
        1e-6,
        "freight = " + BASE_FREIGHT + " × multiplier for region " + region);
  }

  @Test
  void appliesMultiplierWhenAddressPurposeIsCobrancaEntrega() {
    Order order = Orders.fisica(0, Addresses.cobrancaEntrega(Region.SUL));
    RecordingTaxRateCalculator calculator = new RecordingTaxRateCalculator();
    GenerateInvoiceUseCase service = TestUseCases.generateInvoiceUseCase(calculator);

    Invoice invoice = service.generateInvoice(order);

    assertBigDecimalEquals(106.0, invoice.getFreightValue());
  }
}
