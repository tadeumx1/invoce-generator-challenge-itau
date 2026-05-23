package br.com.itau.invoicegenerator.adapter.messaging;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.InvoiceSideEffectDispatcher;

/**
 * Publishes the four invoice integration events durably through Kafka. HTTP success implies that
 * all four publications were acknowledged by the broker; it does not imply that the downstream
 * services have completed their work.
 *
 * <p>Wired in {@code KafkaMessagingConfig} only when {@link IntegrationEventPublisher} is on the
 * context. Tests that exclude Kafka auto-configuration substitute a no-op dispatcher.
 */
public class KafkaInvoiceSideEffectDispatcher implements InvoiceSideEffectDispatcher {

  public static final String EVENT_TYPE_STOCK_DEDUCTION = "STOCK_DEDUCTION";
  public static final String EVENT_TYPE_INVOICE_REGISTRATION = "INVOICE_REGISTRATION";
  public static final String EVENT_TYPE_DELIVERY_SCHEDULING = "DELIVERY_SCHEDULING";
  public static final String EVENT_TYPE_ACCOUNTS_RECEIVABLE = "ACCOUNTS_RECEIVABLE";

  private final IntegrationEventPublisher publisher;

  public KafkaInvoiceSideEffectDispatcher(IntegrationEventPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public void dispatch(Invoice invoice) {
    publisher.publish(
        InvoiceTopics.STOCK_DEDUCTION,
        IntegrationEvent.forInvoice(EVENT_TYPE_STOCK_DEDUCTION, invoice));
    publisher.publish(
        InvoiceTopics.INVOICE_REGISTRATION,
        IntegrationEvent.forInvoice(EVENT_TYPE_INVOICE_REGISTRATION, invoice));
    publisher.publish(
        InvoiceTopics.DELIVERY_SCHEDULING,
        IntegrationEvent.forInvoice(EVENT_TYPE_DELIVERY_SCHEDULING, invoice));
    publisher.publish(
        InvoiceTopics.ACCOUNTS_RECEIVABLE,
        IntegrationEvent.forInvoice(EVENT_TYPE_ACCOUNTS_RECEIVABLE, invoice));
  }
}
