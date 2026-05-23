package br.com.itau.invoicegenerator.adapter.integration.delivery;

import br.com.itau.invoicegenerator.adapter.integration.IntegrationAdapterException;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.DeliveryPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

@Component
public class DeliveryIntegrationAdapter implements DeliveryPort {

  public static final String CIRCUIT_BREAKER_NAME = "deliveryPort";

  private final DeliverySchedulingClient deliverySchedulingClient;

  public DeliveryIntegrationAdapter(DeliverySchedulingClient deliverySchedulingClient) {
    this.deliverySchedulingClient = deliverySchedulingClient;
  }

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER_NAME)
  public void scheduleDelivery(Invoice invoice) {
    try {
      // Simulates the delivery scheduling step.
      Thread.sleep(150);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IntegrationAdapterException(
          CIRCUIT_BREAKER_NAME, "interrupted during delivery scheduling pre-step", e);
    }
    deliverySchedulingClient.createDeliverySchedule(invoice);
  }
}
