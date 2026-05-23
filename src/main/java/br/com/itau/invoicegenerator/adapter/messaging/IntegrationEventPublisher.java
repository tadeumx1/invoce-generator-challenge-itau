package br.com.itau.invoicegenerator.adapter.messaging;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Publishes {@link IntegrationEvent} envelopes to Kafka and waits for the broker acknowledgement so
 * the HTTP response semantic is "invoice generated and Kafka dispatch accepted".
 *
 * <p>Wired in {@code KafkaMessagingConfig} only when a {@link KafkaTemplate} bean exists. In tests
 * that exclude {@code KafkaAutoConfiguration}, a test no-op {@code InvoiceSideEffectDispatcher}
 * stands in.
 */
public class IntegrationEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(IntegrationEventPublisher.class);
  private static final long PUBLISH_TIMEOUT_SECONDS = 5L;

  private final KafkaTemplate<String, IntegrationEvent> kafkaTemplate;

  public IntegrationEventPublisher(KafkaTemplate<String, IntegrationEvent> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public SendResult<String, IntegrationEvent> publish(String topic, IntegrationEvent event) {
    try {
      SendResult<String, IntegrationEvent> result =
          kafkaTemplate
              .send(topic, event.invoiceId(), event)
              .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      log.debug(
          "kafka publish ok topic={} eventId={} invoiceId={} partition={} offset={}",
          topic,
          event.eventId(),
          event.invoiceId(),
          result.getRecordMetadata().partition(),
          result.getRecordMetadata().offset());
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IntegrationEventPublishException(topic, event, e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IntegrationEventPublishException(topic, event, e);
    }
  }
}
