package br.com.itau.invoicegenerator.adapter.observability;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Lifts the invoice correlation / invoice / order id headers off a Kafka record into MDC before the
 * listener method runs, and clears them in the success / failure callbacks. Trace context ({@code
 * traceId} / {@code spanId}) is handled by Spring Kafka's Observation listener automatically — this
 * interceptor only covers the business-correlation MDC entries.
 *
 * <p>When the producer omitted {@code correlationId} (third-party message, downgrade path, etc.), a
 * fresh UUID is synthesized and the gap is WARN-logged once so the operator can find the upstream
 * producer that needs fixing.
 */
public class MdcRestoringRecordInterceptor implements RecordInterceptor<String, Object> {

  private static final Logger log = LoggerFactory.getLogger(MdcRestoringRecordInterceptor.class);

  @Override
  public ConsumerRecord<String, Object> intercept(
      ConsumerRecord<String, Object> record, Consumer<String, Object> consumer) {
    putHeaderInMdc(record, InvoiceKafkaHeaders.CORRELATION_ID, true);
    putHeaderInMdc(record, InvoiceKafkaHeaders.INVOICE_ID, false);
    putHeaderInMdc(record, InvoiceKafkaHeaders.ORDER_ID, false);
    return record;
  }

  @Override
  public void success(ConsumerRecord<String, Object> record, Consumer<String, Object> consumer) {
    clear();
  }

  @Override
  public void failure(
      ConsumerRecord<String, Object> record,
      Exception exception,
      Consumer<String, Object> consumer) {
    clear();
  }

  @Override
  public void afterRecord(
      ConsumerRecord<String, Object> record, Consumer<String, Object> consumer) {
    // Defensive — success/failure should have cleared already.
    clear();
  }

  private static void putHeaderInMdc(
      ConsumerRecord<String, Object> record, String headerName, boolean synthesizeIfMissing) {
    Header header = record.headers().lastHeader(headerName);
    String value = header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    if (value == null && synthesizeIfMissing) {
      value = UUID.randomUUID().toString();
      log.warn(
          "kafka record missing {} header, synthesized fresh value topic={} partition={} offset={}",
          headerName,
          record.topic(),
          record.partition(),
          record.offset());
    }
    if (value != null) {
      MDC.put(headerName, value);
    }
  }

  private static void clear() {
    MDC.remove(InvoiceKafkaHeaders.CORRELATION_ID);
    MDC.remove(InvoiceKafkaHeaders.INVOICE_ID);
    MDC.remove(InvoiceKafkaHeaders.ORDER_ID);
  }
}
