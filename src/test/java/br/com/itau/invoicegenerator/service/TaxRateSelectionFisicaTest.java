package br.com.itau.invoicegenerator.service;

import br.com.itau.invoicegenerator.model.Invoice;
import br.com.itau.invoicegenerator.model.Order;
import br.com.itau.invoicegenerator.service.impl.InvoiceGeneratorServiceImpl;
import br.com.itau.invoicegenerator.testsupport.Orders;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SAFETY-01..05 — FISICA tax-rate selection by totalItemsValue bracket.
 * The calculator is mocked; only the *rate argument* is asserted.
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
        ProductTaxRateCalculator calculator = mock(ProductTaxRateCalculator.class);
        when(calculator.calculateTax(any(), Mockito.anyDouble())).thenReturn(List.of());

        Order order = Orders.fisica(totalItemsValue);
        InvoiceGeneratorServiceImpl service = new InvoiceGeneratorServiceImpl(calculator);

        Invoice ignored = service.generateInvoice(order);

        verify(calculator).calculateTax(order.getItems(), expectedRate);
    }
}
