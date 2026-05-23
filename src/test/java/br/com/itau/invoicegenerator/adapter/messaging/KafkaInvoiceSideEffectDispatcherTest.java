package br.com.itau.invoicegenerator.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.SendResult;

class KafkaInvoiceSideEffectDispatcherTest {

  @Test
  void dispatchPublishesOneEventPerIntegrationTopic() {
    RecordingPublisher recording = new RecordingPublisher();
    KafkaInvoiceSideEffectDispatcher dispatcher = new KafkaInvoiceSideEffectDispatcher(recording);
    Invoice invoice = Invoice.builder().invoiceId("inv-1").build();

    dispatcher.dispatch(invoice);

    assertEquals(4, recording.calls.size(), "expected 4 publishes (one per integration)");
    Set<String> topics =
        recording.calls.stream().map(c -> c.topic).collect(Collectors.toUnmodifiableSet());
    assertEquals(
        Set.of(
            InvoiceTopics.STOCK_DEDUCTION,
            InvoiceTopics.INVOICE_REGISTRATION,
            InvoiceTopics.DELIVERY_SCHEDULING,
            InvoiceTopics.ACCOUNTS_RECEIVABLE),
        topics);
  }

  @Test
  void eachIntegrationEventHasItsTypedEventType() {
    RecordingPublisher recording = new RecordingPublisher();
    new KafkaInvoiceSideEffectDispatcher(recording)
        .dispatch(Invoice.builder().invoiceId("inv-2").build());

    for (RecordingPublisher.Call call : recording.calls) {
      String expected =
          switch (call.topic) {
            case InvoiceTopics.STOCK_DEDUCTION ->
                KafkaInvoiceSideEffectDispatcher.EVENT_TYPE_STOCK_DEDUCTION;
            case InvoiceTopics.INVOICE_REGISTRATION ->
                KafkaInvoiceSideEffectDispatcher.EVENT_TYPE_INVOICE_REGISTRATION;
            case InvoiceTopics.DELIVERY_SCHEDULING ->
                KafkaInvoiceSideEffectDispatcher.EVENT_TYPE_DELIVERY_SCHEDULING;
            case InvoiceTopics.ACCOUNTS_RECEIVABLE ->
                KafkaInvoiceSideEffectDispatcher.EVENT_TYPE_ACCOUNTS_RECEIVABLE;
            default -> throw new AssertionError("unexpected topic " + call.topic);
          };
      assertEquals(expected, call.event.eventType(), "topic=" + call.topic);
      assertEquals("inv-2", call.event.invoiceId());
      assertEquals(IntegrationEvent.CURRENT_VERSION, call.event.version());
      assertNotNull(call.event.eventId());
      assertNotNull(call.event.occurredAt());
    }
  }

  @Test
  void eventIdsAreUniqueAcrossTheFourEvents() {
    RecordingPublisher recording = new RecordingPublisher();
    new KafkaInvoiceSideEffectDispatcher(recording)
        .dispatch(Invoice.builder().invoiceId("inv-3").build());

    long uniqueIds = recording.calls.stream().map(c -> c.event.eventId()).distinct().count();
    assertEquals(4, uniqueIds, "each integration event must have a fresh eventId");
    // sanity: different from any common UUID
    assertNotEquals(recording.calls.get(0).event.eventId(), recording.calls.get(1).event.eventId());
  }

  private static final class RecordingPublisher extends IntegrationEventPublisher {

    final List<Call> calls = new ArrayList<>();

    RecordingPublisher() {
      super(
          null,
          new br.com.itau.invoicegenerator.adapter.observability.InvoiceMetricsRecorder(
              new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @Override
    public SendResult<String, IntegrationEvent> publish(String topic, IntegrationEvent event) {
      calls.add(new Call(topic, event));
      return null;
    }

    record Call(String topic, IntegrationEvent event) {}
  }
}
