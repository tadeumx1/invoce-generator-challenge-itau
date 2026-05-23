package br.com.itau.invoicegenerator.application;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.Order;

public interface GenerateInvoiceUseCase {

  Invoice generateInvoice(Order order);
}
