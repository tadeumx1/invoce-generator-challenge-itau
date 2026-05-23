package br.com.itau.invoicegenerator.adapter.messaging;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * JSON envelope published to Kafka for any invoice side-effect integration. The envelope is
 * versioned ({@link #version}) and carries the canonical invoice payload required by every
 * consumer.
 *
 * <p>Java records are deserialized natively by Jackson via their canonical constructor; explicit
 * {@code @JsonProperty} keeps the wire keys stable across renames.
 */
public record IntegrationEvent(
    @JsonProperty("eventId") String eventId,
    @JsonProperty("eventType") String eventType,
    @JsonProperty("version") int version,
    @JsonProperty("occurredAt") Instant occurredAt,
    @JsonProperty("invoiceId") String invoiceId,
    @JsonProperty("invoice") Invoice invoice) {

  public static final int CURRENT_VERSION = 1;

  public static IntegrationEvent forInvoice(String eventType, Invoice invoice) {
    return new IntegrationEvent(
        UUID.randomUUID().toString(),
        eventType,
        CURRENT_VERSION,
        Instant.now(),
        invoice.getInvoiceId(),
        invoice);
  }
}
