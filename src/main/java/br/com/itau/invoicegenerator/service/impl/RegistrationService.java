package br.com.itau.invoicegenerator.service.impl;

import br.com.itau.invoicegenerator.model.Invoice;

public class RegistrationService {

  public void registerInvoice(Invoice invoice) {
    try {
      // Simulates registering the invoice with the registry.
      Thread.sleep(500);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
