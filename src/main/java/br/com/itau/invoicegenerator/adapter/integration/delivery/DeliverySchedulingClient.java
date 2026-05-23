package br.com.itau.invoicegenerator.adapter.integration.delivery;

import br.com.itau.invoicegenerator.adapter.integration.IntegrationAdapterException;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import org.springframework.stereotype.Component;

/**
 * Inner client used by {@link DeliveryIntegrationAdapter}. The C-6 "+5 s on >5-item orders" trap
 * lives here — the sleep stays as the simulation, but the C-8 interrupt-flag-preservation fix
 * applies.
 */
@Component
public class DeliverySchedulingClient {

  public static final String CIRCUIT_BREAKER_NAME = "deliveryPort";

  public void createDeliverySchedule(Invoice invoice) {
    try {
      if (invoice.getItems().size() > 5) {
        // Known performance trap on orders with more than 5 items. The README states this
        // represents a real upstream constraint, not a coding mistake to remove. See
        // docs/business-rules.md §5.
        Thread.sleep(5000);
      }
      Thread.sleep(200);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IntegrationAdapterException(
          CIRCUIT_BREAKER_NAME, "interrupted during delivery scheduling call", e);
    }
  }
}
