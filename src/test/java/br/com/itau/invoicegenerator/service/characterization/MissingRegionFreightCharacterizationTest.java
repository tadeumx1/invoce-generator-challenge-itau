package br.com.itau.invoicegenerator.service.characterization;

import br.com.itau.invoicegenerator.model.Invoice;
import br.com.itau.invoicegenerator.model.Order;
import br.com.itau.invoicegenerator.model.Region;
import br.com.itau.invoicegenerator.service.ProductTaxRateCalculator;
import br.com.itau.invoicegenerator.service.impl.InvoiceGeneratorServiceImpl;
import br.com.itau.invoicegenerator.testsupport.Addresses;
import br.com.itau.invoicegenerator.testsupport.Orders;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SAFETY-18, SAFETY-19 — characterization of defect C-3 (missing-region freight = 0).
 * See docs/business-rules.md §6.3 and .specs/codebase/CONCERNS.md C-3.
 * <p>
 * M2 (F-DEFECTS-FUNCTIONAL) will replace these assertions with the corrected behavior
 * — either reject the request, or pass freight through unchanged. Decision pending.
 */
class MissingRegionFreightCharacterizationTest {

    @Test
    void freightDropsToZeroWhenNoAddressHasDeliveryPurpose_C3() {
        // Recipient only has COBRANCA addresses; no ENTREGA / COBRANCA_ENTREGA → region lookup yields null.
        Order order = Orders.fisica(0, Addresses.cobranca(Region.SUDESTE));
        ProductTaxRateCalculator calculator = mock(ProductTaxRateCalculator.class);
        when(calculator.calculateTax(any(), anyDouble())).thenReturn(List.of());
        InvoiceGeneratorServiceImpl service = new InvoiceGeneratorServiceImpl(calculator);

        Invoice invoice = service.generateInvoice(order);

        assertEquals(0.0, invoice.getFreightValue(),
                "C-3: with no delivery address, adjustedFreight defaults to 0 (will be flipped in M2)");
    }

    /**
     * SPEC_DEVIATION (SAFETY-19): the spec.md described this case as "freight = 0", same as
     * SAFETY-18. The actual behavior is harsher — {@code Stream.findFirst()} throws NPE when the
     * first element of the post-map stream is null. This characterizes the true defect.
     * Reason: discovered during Execute; spec.md and CONCERNS.md updated to match.
     */
    @Test
    void deliveryAddressWithNullRegionThrowsNpe_C3() {
        Order order = Orders.fisica(0, Addresses.entregaWithNullRegion());
        ProductTaxRateCalculator calculator = mock(ProductTaxRateCalculator.class);
        when(calculator.calculateTax(any(), anyDouble())).thenReturn(List.of());
        InvoiceGeneratorServiceImpl service = new InvoiceGeneratorServiceImpl(calculator);

        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> service.generateInvoice(order),
                "C-3: ENTREGA address with null region causes Stream.findFirst() NPE today (M2 fixes this)"
        );
    }
}
