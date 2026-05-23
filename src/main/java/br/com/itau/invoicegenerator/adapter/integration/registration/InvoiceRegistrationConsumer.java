package br.com.itau.invoicegenerator.adapter.integration.registration;

import br.com.itau.invoicegenerator.adapter.messaging.IdempotencyStore;
import br.com.itau.invoicegenerator.adapter.messaging.IntegrationEvent;
import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.port.InvoiceRegistrationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;

/** Consumes invoice registration events and invokes {@link InvoiceRegistrationPort}. */
public class InvoiceRegistrationConsumer {

  private static final Logger log = LoggerFactory.getLogger(InvoiceRegistrationConsumer.class);

  private final InvoiceRegistrationPort invoiceRegistrationPort;
  private final IdempotencyStore idempotencyStore;

  public InvoiceRegistrationConsumer(
      InvoiceRegistrationPort invoiceRegistrationPort, IdempotencyStore idempotencyStore) {
    this.invoiceRegistrationPort = invoiceRegistrationPort;
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
      topics = InvoiceTopics.INVOICE_REGISTRATION,
      groupId = "invoice-generator-registration")
  public void onEvent(IntegrationEvent event, Acknowledgment ack) {
    if (idempotencyStore.alreadyProcessed(InvoiceTopics.INVOICE_REGISTRATION, event.eventId())) {
      log.info(
          "dedupe invoice registration event eventId={} invoiceId={}",
          event.eventId(),
          event.invoiceId());
      ack.acknowledge();
      return;
    }
    log.debug(
        "consuming invoice registration event eventId={} invoiceId={}",
        event.eventId(),
        event.invoiceId());
    invoiceRegistrationPort.registerInvoice(event.invoice());
    idempotencyStore.markProcessed(InvoiceTopics.INVOICE_REGISTRATION, event.eventId());
    ack.acknowledge();
  }
}
