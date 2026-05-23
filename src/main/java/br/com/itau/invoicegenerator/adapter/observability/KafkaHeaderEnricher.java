package br.com.itau.invoicegenerator.adapter.observability;

import br.com.itau.invoicegenerator.adapter.messaging.IntegrationEvent;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;

/**
 * Builds a {@link ProducerRecord} for an {@link IntegrationEvent}, populating the four
 * correlation/timing headers ({@code correlationId}, {@code invoiceId}, {@code orderId}, {@code
 * publishedAtEpochMillis}) defined in {@link InvoiceKafkaHeaders}. Trace propagation ({@code
 * traceparent}) is handled by Spring Kafka's Observation API on the {@code KafkaTemplate}, not
 * here.
 *
 * <p>{@code correlationId} and {@code orderId} are read from MDC, since they belong to the HTTP
 * request (not the {@link IntegrationEvent} payload). When MDC is empty (e.g., scheduled-job
 * producers in the future), only the headers we can populate are set.
 */
public final class KafkaHeaderEnricher {

  private KafkaHeaderEnricher() {}

  public static ProducerRecord<String, IntegrationEvent> enrich(
      String topic, IntegrationEvent event) {
    ProducerRecord<String, IntegrationEvent> record =
        new ProducerRecord<>(topic, event.invoiceId(), event);
    putHeader(record, InvoiceKafkaHeaders.INVOICE_ID, event.invoiceId());
    putHeader(
        record, InvoiceKafkaHeaders.CORRELATION_ID, MDC.get(InvoiceKafkaHeaders.CORRELATION_ID));
    putHeader(record, InvoiceKafkaHeaders.ORDER_ID, MDC.get(InvoiceKafkaHeaders.ORDER_ID));
    putHeader(
        record,
        InvoiceKafkaHeaders.PUBLISHED_AT_EPOCH_MILLIS,
        Long.toString(System.currentTimeMillis()));
    return record;
  }

  private static void putHeader(
      ProducerRecord<String, IntegrationEvent> record, String name, String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }
}
