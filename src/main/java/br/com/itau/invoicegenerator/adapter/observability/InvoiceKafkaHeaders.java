package br.com.itau.invoicegenerator.adapter.observability;

/**
 * Kafka header names used to carry correlation metadata across the producer/consumer boundary.
 * Spring Kafka's Observation API handles {@code traceparent} on its own; everything else lives here
 * so producers and consumers stay in sync.
 */
public final class InvoiceKafkaHeaders {

  /** Lifetime correlation identifier across HTTP + Kafka + downstream services. */
  public static final String CORRELATION_ID = "correlationId";

  /** Generated invoice UUID, surfaced in logs and metrics. */
  public static final String INVOICE_ID = "invoiceId";

  /** Source order id, surfaced in logs and metrics. */
  public static final String ORDER_ID = "orderId";

  /**
   * Producer-side publish timestamp (epoch millis). Used by the consumer to compute the
   * producer-publish → consumer-ack latency (SLI-4) without requiring clock-aligned servers.
   */
  public static final String PUBLISHED_AT_EPOCH_MILLIS = "publishedAtEpochMillis";

  private InvoiceKafkaHeaders() {}
}
