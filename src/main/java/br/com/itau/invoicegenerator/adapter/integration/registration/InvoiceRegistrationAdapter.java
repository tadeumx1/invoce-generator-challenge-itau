package br.com.itau.invoicegenerator.adapter.integration.registration;

import br.com.itau.invoicegenerator.adapter.integration.IntegrationAdapterException;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.InvoiceRegistrationPort;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InvoiceRegistrationAdapter implements InvoiceRegistrationPort {

  public static final String CIRCUIT_BREAKER_NAME = "invoiceRegistrationPort";

  private static final Logger log = LoggerFactory.getLogger(InvoiceRegistrationAdapter.class);

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER_NAME)
  @Bulkhead(name = CIRCUIT_BREAKER_NAME)
  public void registerInvoice(Invoice invoice) {
    long startNanos = System.nanoTime();
    log.debug("adapter enter port={} invoiceId={}", CIRCUIT_BREAKER_NAME, invoice.getInvoiceId());
    try {
      // Simulates registering the invoice with the registry.
      Thread.sleep(500);
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
          CIRCUIT_BREAKER_NAME, "interrupted during invoice registration call", e);
    }
  }
}
