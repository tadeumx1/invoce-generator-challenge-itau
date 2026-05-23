package br.com.itau.invoicegenerator.service;

import br.com.itau.invoicegenerator.model.CompanyTaxRegime;
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
 * SAFETY-06..09 — JURIDICA × SIMPLES_NACIONAL tax-rate brackets.
 */
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
        ProductTaxRateCalculator calculator = mock(ProductTaxRateCalculator.class);
        when(calculator.calculateTax(any(), Mockito.anyDouble())).thenReturn(List.of());

        Order order = Orders.juridica(totalItemsValue, CompanyTaxRegime.SIMPLES_NACIONAL);
        InvoiceGeneratorServiceImpl service = new InvoiceGeneratorServiceImpl(calculator);

        Invoice ignored = service.generateInvoice(order);

        verify(calculator).calculateTax(order.getItems(), expectedRate);
    }
}
