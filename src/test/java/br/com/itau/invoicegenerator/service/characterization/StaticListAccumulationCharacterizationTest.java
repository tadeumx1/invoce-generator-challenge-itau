package br.com.itau.invoicegenerator.service.characterization;

import br.com.itau.invoicegenerator.model.Invoice;
import br.com.itau.invoicegenerator.model.Order;
import br.com.itau.invoicegenerator.service.ProductTaxRateCalculator;
import br.com.itau.invoicegenerator.service.impl.InvoiceGeneratorServiceImpl;
import br.com.itau.invoicegenerator.testsupport.Orders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SAFETY-20 — characterization of defect C-1 (cross-call item accumulation through the service).
 * See docs/business-rules.md §6.1 and .specs/codebase/CONCERNS.md C-1.
 * <p>
 * M2 will flip the second assertion to expect {@code items.size() == 1} after the calculator
 * is made per-request stateless.
 */
class StaticListAccumulationCharacterizationTest {

    @Test
    void twoCallsOnSameServiceInstanceLeakItems_C1() {
        // Instantiate manually so we observe the bug deterministically without depending on the
        // Spring singleton being shared between this test and others.
        ProductTaxRateCalculator calculator = new ProductTaxRateCalculator();
        InvoiceGeneratorServiceImpl service = new InvoiceGeneratorServiceImpl(calculator);

        Order first = Orders.fisica(400);
        Order second = Orders.fisica(400);

        Invoice firstInvoice = service.generateInvoice(first);
        Invoice secondInvoice = service.generateInvoice(second);

        // Both invoices reference the same shared, growing list.
        assertEquals(2, secondInvoice.getItems().size(),
                "C-1: second invoice contains items from both calls (M2 flips to == 1)");
        assertEquals(2, firstInvoice.getItems().size(),
                "C-1: the first invoice's items reference also points at the leaked list");
    }
}
