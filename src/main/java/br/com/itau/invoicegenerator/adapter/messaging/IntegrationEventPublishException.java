package br.com.itau.invoicegenerator.adapter.messaging;

public class IntegrationEventPublishException extends RuntimeException {

  private final transient IntegrationEvent event;
  private final String topic;

  public IntegrationEventPublishException(String topic, IntegrationEvent event, Throwable cause) {
    super(
        "failed to publish integration event topic="
            + topic
            + " eventId="
            + event.eventId()
            + " invoiceId="
            + event.invoiceId(),
        cause);
    this.topic = topic;
    this.event = event;
  }

  public String getTopic() {
    return topic;
  }

  public IntegrationEvent getEvent() {
    return event;
  }
}
