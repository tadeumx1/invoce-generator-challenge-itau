package br.com.itau.invoicegenerator.adapter.integration.finance;

import br.com.itau.invoicegenerator.adapter.integration.IntegrationAdapterException;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.AccountsReceivablePort;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AccountsReceivableAdapter implements AccountsReceivablePort {

  public static final String CIRCUIT_BREAKER_NAME = "accountsReceivablePort";

  private static final Logger log = LoggerFactory.getLogger(AccountsReceivableAdapter.class);

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER_NAME)
  @Bulkhead(name = CIRCUIT_BREAKER_NAME)
  public void sendInvoiceToAccountsReceivable(Invoice invoice) {
    long startNanos = System.nanoTime();
    log.debug("adapter enter port={} invoiceId={}", CIRCUIT_BREAKER_NAME, invoice.getInvoiceId());
    try {
      // Simulates forwarding the invoice to accounts receivable.
      Thread.sleep(250);
      log.debug(
          "adapter ok port={} invoiceId={} elapsedMs={}",
          CIRCUIT_BREAKER_NAME,
          invoice.getInvoiceId(),
          (System.nanoTime() - startNanos) / 1_000_000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn(
          "adapter fail port={} invoiceId={} exceptionClass={} reason=interrupted",
          CIRCUIT_BREAKER_NAME,
          invoice.getInvoiceId(),
          e.getClass().getName());
      throw new IntegrationAdapterException(
          CIRCUIT_BREAKER_NAME, "interrupted during accounts receivable call", e);
    }
  }
}
