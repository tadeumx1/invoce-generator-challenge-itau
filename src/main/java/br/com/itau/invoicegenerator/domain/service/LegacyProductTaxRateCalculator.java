package br.com.itau.invoicegenerator.domain.service;

import br.com.itau.invoicegenerator.domain.model.InvoiceItem;
import br.com.itau.invoicegenerator.domain.model.Item;
import br.com.itau.invoicegenerator.domain.model.Money;
import br.com.itau.invoicegenerator.domain.port.TaxRateCalculator;
import java.math.BigDecimal;
import java.util.List;

public class LegacyProductTaxRateCalculator implements TaxRateCalculator {

  @Override
  public List<InvoiceItem> calculateTax(List<Item> items, BigDecimal taxRate) {
    return items.stream().map(item -> invoiceItem(item, taxRate)).toList();
  }

  private InvoiceItem invoiceItem(Item item, BigDecimal taxRate) {
    BigDecimal itemTaxValue = Money.rounded(item.getUnitPrice().multiply(taxRate));
    return InvoiceItem.builder()
        .itemId(item.getItemId())
        .description(item.getDescription())
        .unitPrice(item.getUnitPrice())
        .quantity(item.getQuantity())
        .itemTaxValue(itemTaxValue)
        .build();
  }
}
