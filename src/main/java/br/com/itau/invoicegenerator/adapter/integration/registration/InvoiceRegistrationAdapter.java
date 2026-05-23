package br.com.itau.invoicegenerator.adapter.integration.registration;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.InvoiceRegistrationPort;
import org.springframework.stereotype.Component;

@Component
public class InvoiceRegistrationAdapter implements InvoiceRegistrationPort {

  @Override
  public void registerInvoice(Invoice invoice) {
    try {
      // Simulates registering the invoice with the registry.
      Thread.sleep(500);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
