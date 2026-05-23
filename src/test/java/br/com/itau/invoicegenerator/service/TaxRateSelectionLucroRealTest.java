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
 * SAFETY-10 — JURIDICA × LUCRO_REAL tax-rate brackets.
 */
class TaxRateSelectionLucroRealTest {

    @ParameterizedTest(name = "LUCRO_REAL totalItemsValue={0} → rate={1}")
    @CsvSource({
            "0.0, 0.03",
            "999.99, 0.03",
            "1000.0, 0.09",
            "2000.0, 0.09",
            "2000.01, 0.15",
            "5000.0, 0.15",
            "5000.01, 0.20",
            "10000.0, 0.20"
    })
    void selectsCorrectRateForLucroReal(double totalItemsValue, double expectedRate) {
        ProductTaxRateCalculator calculator = mock(ProductTaxRateCalculator.class);
        when(calculator.calculateTax(any(), Mockito.anyDouble())).thenReturn(List.of());

        Order order = Orders.juridica(totalItemsValue, CompanyTaxRegime.LUCRO_REAL);
        InvoiceGeneratorServiceImpl service = new InvoiceGeneratorServiceImpl(calculator);

        Invoice ignored = service.generateInvoice(order);

        verify(calculator).calculateTax(order.getItems(), expectedRate);
    }
}
