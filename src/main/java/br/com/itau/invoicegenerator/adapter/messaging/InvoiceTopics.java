package br.com.itau.invoicegenerator.adapter.messaging;

/**
 * Kafka topic names for the four invoice side-effect integrations. Keep these constants in one
 * place so producers, consumers, retry-topic suffixes, and DLT references stay in sync.
 *
 * <p>Topology decisions are documented in {@code .specs/features/defects-performance/spec.md}: 3
 * partitions per main topic; message key is the invoice UUID; retry topics suffix
 * `.retry.{1m,5m,30m}`; dead-letter topic suffix `.dlt`.
 */
public final class InvoiceTopics {

  public static final String STOCK_DEDUCTION = "invoice.stock-deduction.v1";
  public static final String INVOICE_REGISTRATION = "invoice.registration.v1";
  public static final String DELIVERY_SCHEDULING = "invoice.delivery-scheduling.v1";
  public static final String ACCOUNTS_RECEIVABLE = "invoice.accounts-receivable.v1";

  public static final int PARTITIONS = 3;
  public static final short REPLICATION_FACTOR = 1;

  private InvoiceTopics() {}
}
