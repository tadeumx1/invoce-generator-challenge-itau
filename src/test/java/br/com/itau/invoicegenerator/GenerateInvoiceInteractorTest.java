package br.com.itau.invoicegenerator;

import static br.com.itau.invoicegenerator.testsupport.MoneyAssertions.assertBigDecimalEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.InvoiceItem;
import br.com.itau.invoicegenerator.domain.model.Money;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.testsupport.Orders;
import br.com.itau.invoicegenerator.testsupport.RecordingTaxRateCalculator;
import br.com.itau.invoicegenerator.testsupport.TestUseCases;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAFETY-27, SAFETY-28, SAFETY-29 — proves that the calculator is now actually injected and that a
 * test double's returned items are observed by the SUT.
 */
class GenerateInvoiceInteractorTest {

  @Test
  void usesRateZeroForFisicaUnder500AndReturnsCalculatorResult() {
    RecordingTaxRateCalculator productTaxRateCalculator = new RecordingTaxRateCalculator();
    GenerateInvoiceUseCase invoiceGeneratorService =
        TestUseCases.generateInvoiceUseCase(productTaxRateCalculator);
    Order order = Orders.fisica(400);
    InvoiceItem stub = InvoiceItem.builder().itemId("stub").itemTaxValue(Money.of(0.0)).build();
    productTaxRateCalculator.returnItems(List.of(stub));

    Invoice invoice = invoiceGeneratorService.generateInvoice(order);

    assertSame(order.getItems(), productTaxRateCalculator.lastItems());
    assertBigDecimalEquals(0.0, productTaxRateCalculator.lastRate());
    assertEquals(1, invoice.getItems().size());
    assertSame(
        stub,
        invoice.getItems().get(0),
        "SAFETY-29: the test double's stub is observed by the SUT");
    assertEquals(order.getTotalItemsValue(), invoice.getTotalItemsValue());
  }

  @Test
  void usesRate020ForJuridicaLucroPresumidoOver5000AndReturnsCalculatorResult() {
    RecordingTaxRateCalculator productTaxRateCalculator = new RecordingTaxRateCalculator();
    GenerateInvoiceUseCase invoiceGeneratorService =
        TestUseCases.generateInvoiceUseCase(productTaxRateCalculator);
    Order order = Orders.juridica(6000, CompanyTaxRegime.LUCRO_PRESUMIDO);
    InvoiceItem stub = InvoiceItem.builder().itemId("stub").itemTaxValue(Money.of(200.0)).build();
    productTaxRateCalculator.returnItems(List.of(stub));

    Invoice invoice = invoiceGeneratorService.generateInvoice(order);

    assertSame(order.getItems(), productTaxRateCalculator.lastItems());
    assertBigDecimalEquals(0.20, productTaxRateCalculator.lastRate());
    assertEquals(1, invoice.getItems().size());
    assertSame(stub, invoice.getItems().get(0));
    assertEquals(order.getTotalItemsValue(), invoice.getTotalItemsValue());
  }
}
