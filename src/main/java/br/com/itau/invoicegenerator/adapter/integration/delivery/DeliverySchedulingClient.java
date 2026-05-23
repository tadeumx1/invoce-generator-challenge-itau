package br.com.itau.invoicegenerator.adapter.integration.delivery;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import org.springframework.stereotype.Component;

@Component
public class DeliverySchedulingClient {

  public void createDeliverySchedule(Invoice invoice) {
    try {
      // Simulates the upstream delivery scheduling integration.
      if (invoice.getItems().size() > 5) {
        // Known performance trap on orders with more than 5 items.
        // The README states this represents a real upstream constraint, not a coding mistake to
        // remove.
        // See docs/business-rules.md §5.
        Thread.sleep(5000);
      }
      Thread.sleep(200);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
