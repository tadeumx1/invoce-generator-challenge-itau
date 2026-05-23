package br.com.itau.invoicegenerator.adapter.observability;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * On every successful listener invocation, computes producer-publish → consumer-ack latency from
 * the {@link InvoiceKafkaHeaders#PUBLISHED_AT_EPOCH_MILLIS} header and records it on the {@code
 * invoice.sideeffect.duration} timer (SLI-4 source).
 *
 * <p>A missing header is logged at DEBUG and skipped: the consumer is still serviceable, but the
 * SLI-4 timer simply has one less data point.
 */
public class SideEffectTimingConsumerListener implements RecordInterceptor<String, Object> {

  private static final Logger log = LoggerFactory.getLogger(SideEffectTimingConsumerListener.class);

  private final InvoiceMetricsRecorder metricsRecorder;

  public SideEffectTimingConsumerListener(InvoiceMetricsRecorder metricsRecorder) {
    this.metricsRecorder = metricsRecorder;
  }

  @Override
  public ConsumerRecord<String, Object> intercept(
      ConsumerRecord<String, Object> record, Consumer<String, Object> consumer) {
    return record;
  }

  @Override
  public void success(ConsumerRecord<String, Object> record, Consumer<String, Object> consumer) {
    Long publishedAt = readPublishedAt(record);
    if (publishedAt == null) {
      log.debug(
          "kafka record missing {} header; SLI-4 timer skipped topic={} partition={} offset={}",
          InvoiceKafkaHeaders.PUBLISHED_AT_EPOCH_MILLIS,
          record.topic(),
          record.partition(),
          record.offset());
      return;
    }
    try {
      metricsRecorder.recordSideEffect(record.topic(), publishedAt);
    } catch (IllegalArgumentException unknownTopic) {
      log.warn("dropping side-effect timer for unknown topic {}", record.topic());
    }
  }

  private static Long readPublishedAt(ConsumerRecord<String, Object> record) {
    Header header = record.headers().lastHeader(InvoiceKafkaHeaders.PUBLISHED_AT_EPOCH_MILLIS);
    if (header == null || header.value() == null) {
      return null;
    }
    try {
      return Long.parseLong(new String(header.value(), StandardCharsets.UTF_8));
    } catch (NumberFormatException malformed) {
      log.warn(
          "malformed {} header value, skipping side-effect timer topic={}",
          InvoiceKafkaHeaders.PUBLISHED_AT_EPOCH_MILLIS,
          record.topic());
      return null;
    }
  }
}
