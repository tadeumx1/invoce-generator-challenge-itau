package br.com.itau.invoicegenerator;

import br.com.itau.invoicegenerator.model.*;
import br.com.itau.invoicegenerator.service.ProductTaxRateCalculator;
import br.com.itau.invoicegenerator.service.impl.InvoiceGeneratorServiceImpl;
import br.com.itau.invoicegenerator.testsupport.Orders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SAFETY-27, SAFETY-28, SAFETY-29 — proves that the calculator is now actually injected
 * (Mockito's @InjectMocks satisfies the single constructor with the @Mock) and that stubs
 * on the calculator are observed by the SUT.
 */
class InvoiceGeneratorServiceImplTest {

    @InjectMocks
    private InvoiceGeneratorServiceImpl invoiceGeneratorService;

    @Mock
    private ProductTaxRateCalculator productTaxRateCalculator;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void usesRateZeroForFisicaUnder500AndReturnsCalculatorResult() {
        Order order = Orders.fisica(400);
        InvoiceItem stub = InvoiceItem.builder().itemId("stub").itemTaxValue(0.0).build();
        when(productTaxRateCalculator.calculateTax(any(), eq(0.0))).thenReturn(List.of(stub));

        Invoice invoice = invoiceGeneratorService.generateInvoice(order);

        verify(productTaxRateCalculator).calculateTax(order.getItems(), 0.0);
        assertEquals(1, invoice.getItems().size());
        assertSame(stub, invoice.getItems().get(0), "SAFETY-29: the mock's stub is observed by the SUT");
        assertEquals(order.getTotalItemsValue(), invoice.getTotalItemsValue());
    }

    @Test
    void usesRate020ForJuridicaLucroPresumidoOver5000AndReturnsCalculatorResult() {
        Order order = Orders.juridica(6000, CompanyTaxRegime.LUCRO_PRESUMIDO);
        InvoiceItem stub = InvoiceItem.builder().itemId("stub").itemTaxValue(200.0).build();
        when(productTaxRateCalculator.calculateTax(any(), eq(0.20))).thenReturn(List.of(stub));

        Invoice invoice = invoiceGeneratorService.generateInvoice(order);

        verify(productTaxRateCalculator).calculateTax(order.getItems(), 0.20);
        assertEquals(1, invoice.getItems().size());
        assertSame(stub, invoice.getItems().get(0));
        assertEquals(order.getTotalItemsValue(), invoice.getTotalItemsValue());
    }
}
