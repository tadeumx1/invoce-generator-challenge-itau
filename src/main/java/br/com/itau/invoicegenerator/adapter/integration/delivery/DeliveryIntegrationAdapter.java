package br.com.itau.invoicegenerator.adapter.integration.delivery;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.DeliveryPort;
import org.springframework.stereotype.Component;

@Component
public class DeliveryIntegrationAdapter implements DeliveryPort {

  private final DeliverySchedulingClient deliverySchedulingClient;

  public DeliveryIntegrationAdapter(DeliverySchedulingClient deliverySchedulingClient) {
    this.deliverySchedulingClient = deliverySchedulingClient;
  }

  @Override
  public void scheduleDelivery(Invoice invoice) {
    try {
      // Simulates the delivery scheduling step.
      Thread.sleep(150);
      deliverySchedulingClient.createDeliverySchedule(invoice);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
