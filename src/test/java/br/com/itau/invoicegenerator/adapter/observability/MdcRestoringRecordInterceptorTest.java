package br.com.itau.invoicegenerator.adapter.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcRestoringRecordInterceptorTest {

  private final MdcRestoringRecordInterceptor interceptor = new MdcRestoringRecordInterceptor();

  @AfterEach
  void cleanup() {
    MDC.clear();
  }

  @Test
  void interceptPullsHeadersIntoMdc() {
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("topic", 0, 0L, "key", new Object());
    record.headers().add(header(InvoiceKafkaHeaders.CORRELATION_ID, "probe-1"));
    record.headers().add(header(InvoiceKafkaHeaders.INVOICE_ID, "inv-1"));
    record.headers().add(header(InvoiceKafkaHeaders.ORDER_ID, "ord-1"));

    interceptor.intercept(record, null);

    assertEquals("probe-1", MDC.get(InvoiceKafkaHeaders.CORRELATION_ID));
    assertEquals("inv-1", MDC.get(InvoiceKafkaHeaders.INVOICE_ID));
    assertEquals("ord-1", MDC.get(InvoiceKafkaHeaders.ORDER_ID));
  }

  @Test
  void missingCorrelationIdSynthesizesAFreshValue() {
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("topic", 0, 0L, "key", new Object());

    interceptor.intercept(record, null);

    String synthesized = MDC.get(InvoiceKafkaHeaders.CORRELATION_ID);
    assertNotNull(synthesized, "missing correlationId must be synthesized");
    assertEquals(36, synthesized.length());
  }

  @Test
  void successCallbackClearsMdc() {
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("topic", 0, 0L, "key", new Object());
    record.headers().add(header(InvoiceKafkaHeaders.CORRELATION_ID, "probe-2"));

    interceptor.intercept(record, null);
    interceptor.success(record, null);

    assertNull(MDC.get(InvoiceKafkaHeaders.CORRELATION_ID));
    assertNull(MDC.get(InvoiceKafkaHeaders.INVOICE_ID));
    assertNull(MDC.get(InvoiceKafkaHeaders.ORDER_ID));
  }

  @Test
  void failureCallbackAlsoClearsMdc() {
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("topic", 0, 0L, "key", new Object());
    record.headers().add(header(InvoiceKafkaHeaders.CORRELATION_ID, "probe-3"));

    interceptor.intercept(record, null);
    interceptor.failure(record, new RuntimeException("boom"), null);

    assertNull(MDC.get(InvoiceKafkaHeaders.CORRELATION_ID));
  }

  private static RecordHeader header(String name, String value) {
    return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
  }
}
