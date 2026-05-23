package br.com.itau.invoicegenerator.service;

import br.com.itau.invoicegenerator.model.Item;
import br.com.itau.invoicegenerator.model.InvoiceItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ProductTaxRateCalculator {

    // Legacy defect preserved: shared across requests via Spring's default singleton scope
    // (previously via a static field — equivalent observable behavior).
    // See docs/business-rules.md §6.1 / CONCERNS.md C-1; fixed in M2.
    private final List<InvoiceItem> invoiceItemList = new ArrayList<>();

    public List<InvoiceItem> calculateTax(List<Item> items, double taxRate) {

        for (Item item : items) {
            double itemTaxValue = item.getUnitPrice() * taxRate;
            InvoiceItem invoiceItem = InvoiceItem.builder()
                    .itemId(item.getItemId())
                    .description(item.getDescription())
                    .unitPrice(item.getUnitPrice())
                    .quantity(item.getQuantity())
                    .itemTaxValue(itemTaxValue)
                    .build();
            invoiceItemList.add(invoiceItem);
        }
        return invoiceItemList;
    }
}
