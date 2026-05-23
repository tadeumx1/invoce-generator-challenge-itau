package br.com.itau.invoicegenerator.domain.port;

import br.com.itau.invoicegenerator.domain.model.InvoiceItem;
import br.com.itau.invoicegenerator.domain.model.Item;
import java.math.BigDecimal;
import java.util.List;

public interface TaxRateCalculator {

  List<InvoiceItem> calculateTax(List<Item> items, BigDecimal taxRate);
}
