package br.com.itau.invoicegenerator.service;

import br.com.itau.invoicegenerator.model.Invoice;
import br.com.itau.invoicegenerator.model.Order;

public interface InvoiceGeneratorService {

  Invoice generateInvoice(Order order);
}
