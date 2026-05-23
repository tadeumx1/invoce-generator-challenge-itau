package br.com.itau.invoicegenerator.domain.port;

import br.com.itau.invoicegenerator.domain.model.Invoice;

public interface StockPort {

  void sendInvoiceForStockDeduction(Invoice invoice);
}
