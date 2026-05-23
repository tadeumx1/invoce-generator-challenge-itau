package br.com.itau.invoicegenerator.adapter.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.itau.invoicegenerator.adapter.messaging.IntegrationEvent;
import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class KafkaHeaderEnricherTest {

  @AfterEach
  void cleanupMdc() {
    MDC.clear();
  }

  @Test
  void enrichSetsInvoiceIdAndPublishedAtHeaders() {
    IntegrationEvent event =
        IntegrationEvent.forInvoice(
            "STOCK_DEDUCTION", Invoice.builder().invoiceId("inv-1").build());

    ProducerRecord<String, IntegrationEvent> record =
        KafkaHeaderEnricher.enrich(InvoiceTopics.STOCK_DEDUCTION, event);

    assertEquals(InvoiceTopics.STOCK_DEDUCTION, record.topic());
    assertEquals("inv-1", record.key());
    assertEquals("inv-1", headerValue(record, InvoiceKafkaHeaders.INVOICE_ID));
    String publishedAt = headerValue(record, InvoiceKafkaHeaders.PUBLISHED_AT_EPOCH_MILLIS);
    assertNotNull(publishedAt, "publishedAtEpochMillis header must be set");
    assertTrue(Long.parseLong(publishedAt) > 0L);
  }

  @Test
  void enrichPropagatesCorrelationAndOrderIdFromMdc() {
    MDC.put(InvoiceKafkaHeaders.CORRELATION_ID, "probe-1");
    MDC.put(InvoiceKafkaHeaders.ORDER_ID, "ord-1");
    IntegrationEvent event =
        IntegrationEvent.forInvoice(
            "INVOICE_REGISTRATION", Invoice.builder().invoiceId("inv-2").build());

    ProducerRecord<String, IntegrationEvent> record =
        KafkaHeaderEnricher.enrich(InvoiceTopics.INVOICE_REGISTRATION, event);

    assertEquals("probe-1", headerValue(record, InvoiceKafkaHeaders.CORRELATION_ID));
    assertEquals("ord-1", headerValue(record, InvoiceKafkaHeaders.ORDER_ID));
  }

  @Test
  void enrichOmitsCorrelationHeaderWhenMdcIsEmpty() {
    IntegrationEvent event =
        IntegrationEvent.forInvoice(
            "DELIVERY_SCHEDULING", Invoice.builder().invoiceId("inv-3").build());

    ProducerRecord<String, IntegrationEvent> record =
        KafkaHeaderEnricher.enrich(InvoiceTopics.DELIVERY_SCHEDULING, event);

    assertNull(headerValue(record, InvoiceKafkaHeaders.CORRELATION_ID));
    assertNull(headerValue(record, InvoiceKafkaHeaders.ORDER_ID));
  }

  private static String headerValue(ProducerRecord<String, ?> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
