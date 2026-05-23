package br.com.itau.invoicegenerator.adapter.integration.delivery;

import br.com.itau.invoicegenerator.adapter.messaging.IdempotencyStore;
import br.com.itau.invoicegenerator.adapter.messaging.IntegrationEvent;
import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.port.DeliveryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;

/**
 * Consumes delivery scheduling events. This is the consumer that absorbs the +5 s latency on orders
 * with more than 5 items (C-6) — keeping it off the HTTP request thread.
 */
public class DeliverySchedulingConsumer {

  private static final Logger log = LoggerFactory.getLogger(DeliverySchedulingConsumer.class);

  private final DeliveryPort deliveryPort;
  private final IdempotencyStore idempotencyStore;

  public DeliverySchedulingConsumer(DeliveryPort deliveryPort, IdempotencyStore idempotencyStore) {
    this.deliveryPort = deliveryPort;
    this.idempotencyStore = idempotencyStore;
  }

  @RetryableTopic(
      attempts = "${app.kafka.retry.attempts:4}",
      backoff =
          @Backoff(
              delayExpression = "${app.kafka.retry.delay-ms:60000}",
              multiplierExpression = "${app.kafka.retry.multiplier:5.0}"),
      autoCreateTopics = "true",
      dltStrategy = DltStrategy.FAIL_ON_ERROR)
  @KafkaListener(
      topics = InvoiceTopics.DELIVERY_SCHEDULING,
      groupId = "invoice-generator-delivery-scheduling")
  public void onEvent(IntegrationEvent event, Acknowledgment ack) {
    if (idempotencyStore.alreadyProcessed(InvoiceTopics.DELIVERY_SCHEDULING, event.eventId())) {
      log.info(
          "dedupe delivery scheduling event eventId={} invoiceId={}",
          event.eventId(),
          event.invoiceId());
      ack.acknowledge();
      return;
    }
    log.debug(
        "consuming delivery scheduling event eventId={} invoiceId={}",
        event.eventId(),
        event.invoiceId());
    deliveryPort.scheduleDelivery(event.invoice());
    idempotencyStore.markProcessed(InvoiceTopics.DELIVERY_SCHEDULING, event.eventId());
    ack.acknowledge();
  }
}
