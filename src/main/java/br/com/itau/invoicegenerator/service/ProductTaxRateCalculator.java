package br.com.itau.invoicegenerator.service;

import br.com.itau.invoicegenerator.model.Item;
import br.com.itau.invoicegenerator.model.InvoiceItem;

import java.util.ArrayList;
import java.util.List;

public class ProductTaxRateCalculator {

    // NOTE: legacy defect preserved verbatim — this static list accumulates across requests.
    // See docs/business-rules.md §6.1; to be fixed in the refactor.
    private static List<InvoiceItem> invoiceItemList = new ArrayList<>();

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
