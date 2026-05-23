package br.com.itau.invoicegenerator.adapter.messaging;

import br.com.itau.invoicegenerator.adapter.observability.InvoiceMetricsRecorder;
import br.com.itau.invoicegenerator.adapter.observability.KafkaHeaderEnricher;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Publishes {@link IntegrationEvent} envelopes to Kafka and waits for the broker acknowledgement so
 * the HTTP response semantic is "invoice generated and Kafka dispatch accepted". Each publish is
 * timed and reported through {@link InvoiceMetricsRecorder} to feed SLI-3 (dispatch success) and
 * the {@code invoice.dispatch.duration} timer.
 *
 * <p>Wired in {@code KafkaMessagingConfig} only when a {@link KafkaTemplate} bean exists. In tests
 * that exclude {@code KafkaAutoConfiguration}, a test no-op {@code InvoiceSideEffectDispatcher}
 * stands in.
 */
public class IntegrationEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(IntegrationEventPublisher.class);
  private static final long PUBLISH_TIMEOUT_SECONDS = 5L;

  private final KafkaTemplate<String, IntegrationEvent> kafkaTemplate;
  private final InvoiceMetricsRecorder metricsRecorder;

  public IntegrationEventPublisher(
      KafkaTemplate<String, IntegrationEvent> kafkaTemplate,
      InvoiceMetricsRecorder metricsRecorder) {
    this.kafkaTemplate = kafkaTemplate;
    this.metricsRecorder = metricsRecorder;
  }

  public SendResult<String, IntegrationEvent> publish(String topic, IntegrationEvent event) {
    ProducerRecord<String, IntegrationEvent> record = KafkaHeaderEnricher.enrich(topic, event);
    long startNanos = System.nanoTime();
    try {
      SendResult<String, IntegrationEvent> result =
          kafkaTemplate.send(record).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
      metricsRecorder.recordDispatch(topic, true, latency);
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
      metricsRecorder.recordDispatch(
          topic, false, Duration.ofNanos(System.nanoTime() - startNanos));
      throw new IntegrationEventPublishException(topic, event, e);
    } catch (ExecutionException | TimeoutException e) {
      metricsRecorder.recordDispatch(
          topic, false, Duration.ofNanos(System.nanoTime() - startNanos));
      throw new IntegrationEventPublishException(topic, event, e);
    }
  }
}
