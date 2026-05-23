package br.com.itau.invoicegenerator.domain.port;

import br.com.itau.invoicegenerator.domain.model.Invoice;

public interface InvoiceRegistrationPort {

  void registerInvoice(Invoice invoice);
}
