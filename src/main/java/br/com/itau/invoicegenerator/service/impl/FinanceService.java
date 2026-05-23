package br.com.itau.invoicegenerator.service.impl;

import br.com.itau.invoicegenerator.model.Invoice;

public class FinanceService {

  public void sendInvoiceToAccountsReceivable(Invoice invoice) {
    try {
      // Simulates forwarding the invoice to accounts receivable.
      Thread.sleep(250);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
