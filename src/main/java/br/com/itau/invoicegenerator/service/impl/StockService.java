package br.com.itau.invoicegenerator.service.impl;

import br.com.itau.invoicegenerator.model.Invoice;

public class StockService {

  public void sendInvoiceForStockDeduction(Invoice invoice) {
    try {
      // Simulates sending the invoice for stock deduction.
      Thread.sleep(380);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
