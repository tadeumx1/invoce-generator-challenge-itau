package br.com.itau.invoicegenerator.adapter.integration.finance;

import br.com.itau.invoicegenerator.adapter.integration.IntegrationAdapterException;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.AccountsReceivablePort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

@Component
public class AccountsReceivableAdapter implements AccountsReceivablePort {

  public static final String CIRCUIT_BREAKER_NAME = "accountsReceivablePort";

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER_NAME)
  public void sendInvoiceToAccountsReceivable(Invoice invoice) {
    try {
      // Simulates forwarding the invoice to accounts receivable.
      Thread.sleep(250);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IntegrationAdapterException(
          CIRCUIT_BREAKER_NAME, "interrupted during accounts receivable call", e);
    }
  }
}
