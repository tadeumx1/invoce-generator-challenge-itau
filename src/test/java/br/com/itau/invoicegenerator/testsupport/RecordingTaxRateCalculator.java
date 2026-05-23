package br.com.itau.invoicegenerator.testsupport;

import br.com.itau.invoicegenerator.domain.model.InvoiceItem;
import br.com.itau.invoicegenerator.domain.model.Item;
import br.com.itau.invoicegenerator.domain.port.TaxRateCalculator;
import java.math.BigDecimal;
import java.util.List;

public class RecordingTaxRateCalculator implements TaxRateCalculator {

  private List<InvoiceItem> result = List.of();
  private List<Item> lastItems;
  private BigDecimal lastRate;
  private int calls;

  public void returnItems(List<InvoiceItem> result) {
    this.result = result;
  }

  @Override
  public List<InvoiceItem> calculateTax(List<Item> items, BigDecimal taxRate) {
    calls++;
    lastItems = items;
    lastRate = taxRate;
    return result;
  }

  public List<Item> lastItems() {
    return lastItems;
  }

  public BigDecimal lastRate() {
    return lastRate;
  }

  public int calls() {
    return calls;
  }
}
