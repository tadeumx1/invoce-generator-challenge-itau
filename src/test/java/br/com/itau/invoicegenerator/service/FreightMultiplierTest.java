package br.com.itau.invoicegenerator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.itau.invoicegenerator.model.Invoice;
import br.com.itau.invoicegenerator.model.Order;
import br.com.itau.invoicegenerator.model.Region;
import br.com.itau.invoicegenerator.service.impl.InvoiceGeneratorServiceImpl;
import br.com.itau.invoicegenerator.testsupport.Addresses;
import br.com.itau.invoicegenerator.testsupport.Orders;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SAFETY-12..17 — freight multiplier per region; COBRANCA_ENTREGA also triggers the lookup. The
 * freightValue input is fixed at 100.0; expected output is the documented multiplier × 100.
 */
class FreightMultiplierTest {

  private static final double DELTA = 1e-6;
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
    ProductTaxRateCalculator calculator = mock(ProductTaxRateCalculator.class);
    when(calculator.calculateTax(any(), anyDouble())).thenReturn(List.of());
    InvoiceGeneratorServiceImpl service = new InvoiceGeneratorServiceImpl(calculator);

    Invoice invoice = service.generateInvoice(order);

    assertEquals(
        expectedFreight,
        invoice.getFreightValue(),
        DELTA,
        "freight = " + BASE_FREIGHT + " × multiplier for region " + region);
  }

  @Test
  void appliesMultiplierWhenAddressPurposeIsCobrancaEntrega() {
    Order order = Orders.fisica(0, Addresses.cobrancaEntrega(Region.SUL));
    ProductTaxRateCalculator calculator = mock(ProductTaxRateCalculator.class);
    when(calculator.calculateTax(any(), anyDouble())).thenReturn(List.of());
    InvoiceGeneratorServiceImpl service = new InvoiceGeneratorServiceImpl(calculator);

    Invoice invoice = service.generateInvoice(order);

    assertEquals(
        106.0,
        invoice.getFreightValue(),
        DELTA,
        "COBRANCA_ENTREGA must also trigger the region lookup (SAFETY-17)");
  }
}
