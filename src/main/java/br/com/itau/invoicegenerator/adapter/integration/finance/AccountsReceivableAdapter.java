package br.com.itau.invoicegenerator.adapter.integration.finance;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.AccountsReceivablePort;
import org.springframework.stereotype.Component;

@Component
public class AccountsReceivableAdapter implements AccountsReceivablePort {

  @Override
  public void sendInvoiceToAccountsReceivable(Invoice invoice) {
    try {
      // Simulates forwarding the invoice to accounts receivable.
      Thread.sleep(250);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
