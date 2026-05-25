package br.com.itau.invoicegenerator.adapter.integration.delivery;

import br.com.itau.invoicegenerator.adapter.integration.IntegrationAdapterException;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.DeliveryPort;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DeliveryIntegrationAdapter implements DeliveryPort {

  public static final String CIRCUIT_BREAKER_NAME = "deliveryPort";

  private static final Logger log = LoggerFactory.getLogger(DeliveryIntegrationAdapter.class);

  private final DeliverySchedulingClient deliverySchedulingClient;

  public DeliveryIntegrationAdapter(DeliverySchedulingClient deliverySchedulingClient) {
    this.deliverySchedulingClient = deliverySchedulingClient;
  }

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER_NAME)
  @Bulkhead(name = CIRCUIT_BREAKER_NAME)
  public void scheduleDelivery(Invoice invoice) {
    long startNanos = System.nanoTime();
    log.debug("adapter enter port={} invoiceId={}", CIRCUIT_BREAKER_NAME, invoice.getInvoiceId());
    try {
      // Simulates the delivery scheduling step.
      Thread.sleep(150);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn(
          "adapter fail port={} invoiceId={} exceptionClass={} reason=interrupted-pre-step",
          CIRCUIT_BREAKER_NAME,
          invoice.getInvoiceId(),
          e.getClass().getName());
      throw new IntegrationAdapterException(
          CIRCUIT_BREAKER_NAME, "interrupted during delivery scheduling pre-step", e);
    }
    deliverySchedulingClient.createDeliverySchedule(invoice);
    log.debug(
        "adapter ok port={} invoiceId={} elapsedMs={}",
        CIRCUIT_BREAKER_NAME,
        invoice.getInvoiceId(),
        (System.nanoTime() - startNanos) / 1_000_000L);
  }
}
