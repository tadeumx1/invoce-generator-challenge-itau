package br.com.itau.invoicegenerator.service;

import br.com.itau.invoicegenerator.model.Item;
import br.com.itau.invoicegenerator.model.InvoiceItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ProductTaxRateCalculatorTest {

    @Test
    void appliesRateToUnitPriceAndCopiesItemFields() {
        ProductTaxRateCalculator calculator = new ProductTaxRateCalculator();
        Item item = new Item("item-1", "Teclado", 100.0, 3);

        List<InvoiceItem> result = calculator.calculateTax(List.of(item), 0.10);

        assertEquals(1, result.size());
        InvoiceItem out = result.get(0);
        assertEquals("item-1", out.getItemId());
        assertEquals("Teclado", out.getDescription());
        assertEquals(100.0, out.getUnitPrice());
        assertEquals(3, out.getQuantity());
        assertEquals(10.0, out.getItemTaxValue());
    }

    @Test
    void returnsOneInvoiceItemPerInputItemInOrder() {
        ProductTaxRateCalculator calculator = new ProductTaxRateCalculator();
        Item a = new Item("a", "A", 50.0, 1);
        Item b = new Item("b", "B", 200.0, 2);

        List<InvoiceItem> result = calculator.calculateTax(List.of(a, b), 0.05);

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).getItemId());
        assertEquals(2.5, result.get(0).getItemTaxValue());
        assertEquals("b", result.get(1).getItemId());
        assertEquals(10.0, result.get(1).getItemTaxValue());
    }

    /**
     * Characterization of C-1 at the calculator level.
     * Two consecutive calls on the SAME instance accumulate into a single list.
     * M2 will flip this assertion to assert {@code result2.size() == 1}.
     * See docs/business-rules.md §6.1 / .specs/codebase/CONCERNS.md C-1.
     */
    @Test
    void characterization_consecutiveCallsAccumulateOnSameInstance() {
        ProductTaxRateCalculator calculator = new ProductTaxRateCalculator();
        Item first = new Item("first", "First", 100.0, 1);
        Item second = new Item("second", "Second", 200.0, 1);

        calculator.calculateTax(List.of(first), 0.10);
        List<InvoiceItem> result2 = calculator.calculateTax(List.of(second), 0.10);

        assertEquals(2, result2.size(),
                "C-1 characterization: second call returns accumulated list including the first item");

        // Sanity: a fresh calculator instance is isolated.
        ProductTaxRateCalculator fresh = new ProductTaxRateCalculator();
        List<InvoiceItem> isolated = fresh.calculateTax(List.of(first), 0.10);
        assertEquals(1, isolated.size());
        assertNotSame(result2, isolated);
    }
}
