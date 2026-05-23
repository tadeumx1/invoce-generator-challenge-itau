package br.com.itau.invoicegenerator.service.impl;

import br.com.itau.invoicegenerator.model.Invoice;
import br.com.itau.invoicegenerator.port.out.DeliveryIntegrationPort;

public class DeliveryService {

  public void scheduleDelivery(Invoice invoice) {
    try {
      // Simulates the delivery scheduling step.
      Thread.sleep(150);
      new DeliveryIntegrationPort().createDeliverySchedule(invoice);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
