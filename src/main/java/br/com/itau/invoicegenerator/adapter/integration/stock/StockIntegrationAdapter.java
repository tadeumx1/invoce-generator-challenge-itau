package br.com.itau.invoicegenerator.adapter.integration.stock;

import br.com.itau.invoicegenerator.adapter.integration.IntegrationAdapterException;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.StockPort;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StockIntegrationAdapter implements StockPort {

  public static final String CIRCUIT_BREAKER_NAME = "stockPort";

  private static final Logger log = LoggerFactory.getLogger(StockIntegrationAdapter.class);

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER_NAME)
  @Bulkhead(name = CIRCUIT_BREAKER_NAME)
  public void sendInvoiceForStockDeduction(Invoice invoice) {
    long startNanos = System.nanoTime();
    log.debug("adapter enter port={} invoiceId={}", CIRCUIT_BREAKER_NAME, invoice.getInvoiceId());
    try {
      // Simulates sending the invoice for stock deduction.
      Thread.sleep(380);
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
          CIRCUIT_BREAKER_NAME, "interrupted during stock deduction call", e);
    }
  }
}
