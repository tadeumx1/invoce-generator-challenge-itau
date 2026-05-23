package br.com.itau.invoicegenerator.service.characterization;

import br.com.itau.invoicegenerator.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.model.Invoice;
import br.com.itau.invoicegenerator.model.Order;
import br.com.itau.invoicegenerator.service.ProductTaxRateCalculator;
import br.com.itau.invoicegenerator.service.impl.InvoiceGeneratorServiceImpl;
import br.com.itau.invoicegenerator.testsupport.Orders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * SAFETY-21, SAFETY-22 — characterization of defect C-2 (JURIDICA + taxRegime ∈ {OUTROS, null}
 * silently produces empty items).
 * See docs/business-rules.md §6.2 and .specs/codebase/CONCERNS.md C-2.
 * <p>
 * M2 will replace these assertions with the agreed behavior (likely reject the request, or
 * apply a documented default). Decision pending.
 */
class UnhandledTaxRegimeCharacterizationTest {

    @Test
    void juridicaWithOutrosTaxRegimeProducesEmptyItems_C2() {
        ProductTaxRateCalculator calculator = mock(ProductTaxRateCalculator.class);
        InvoiceGeneratorServiceImpl service = new InvoiceGeneratorServiceImpl(calculator);
        Order order = Orders.juridica(1000.0, CompanyTaxRegime.OUTROS);

        Invoice invoice = service.generateInvoice(order);

        assertEquals(0, invoice.getItems().size(),
                "C-2: OUTROS falls through; items end up empty (M2 fixes this)");
        verifyNoInteractions(calculator);
    }

    @Test
    void juridicaWithNullTaxRegimeProducesEmptyItems_C2() {
        ProductTaxRateCalculator calculator = mock(ProductTaxRateCalculator.class);
        InvoiceGeneratorServiceImpl service = new InvoiceGeneratorServiceImpl(calculator);
        Order order = Orders.juridica(1000.0, null);

        Invoice invoice = service.generateInvoice(order);

        assertEquals(0, invoice.getItems().size(),
                "C-2: null taxRegime falls through; items end up empty (M2 fixes this)");
        verifyNoInteractions(calculator);
    }
}
