package br.com.itau.invoicegenerator.adapter.integration.delivery;

import br.com.itau.invoicegenerator.adapter.messaging.IntegrationEvent;
import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.port.DeliveryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Consumes delivery scheduling events. This is the consumer that absorbs the +5 s latency on orders
 * with more than 5 items (C-6) — keeping it off the HTTP request thread.
 */
public class DeliverySchedulingConsumer {

  private static final Logger log = LoggerFactory.getLogger(DeliverySchedulingConsumer.class);

  private final DeliveryPort deliveryPort;

  public DeliverySchedulingConsumer(DeliveryPort deliveryPort) {
    this.deliveryPort = deliveryPort;
  }

  @KafkaListener(
      topics = InvoiceTopics.DELIVERY_SCHEDULING,
      groupId = "invoice-generator-delivery-scheduling")
  public void onEvent(IntegrationEvent event, Acknowledgment ack) {
    log.debug(
        "consuming delivery scheduling event eventId={} invoiceId={}",
        event.eventId(),
        event.invoiceId());
    deliveryPort.scheduleDelivery(event.invoice());
    ack.acknowledge();
  }
}
