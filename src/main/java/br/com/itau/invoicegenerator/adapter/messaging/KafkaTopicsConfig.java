package br.com.itau.invoicegenerator.adapter.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic builders for the four invoice integration topics. Registered as Spring {@link NewTopic}
 * beans by {@code KafkaMessagingConfig}. Retry and dead-letter topics are auto-created by Spring
 * Kafka's {@code @RetryableTopic} annotation under T3.
 */
public final class KafkaTopicsConfig {

  private KafkaTopicsConfig() {}

  public static NewTopic stockDeductionTopic() {
    return TopicBuilder.name(InvoiceTopics.STOCK_DEDUCTION)
        .partitions(InvoiceTopics.PARTITIONS)
        .replicas(InvoiceTopics.REPLICATION_FACTOR)
        .build();
  }

  public static NewTopic invoiceRegistrationTopic() {
    return TopicBuilder.name(InvoiceTopics.INVOICE_REGISTRATION)
        .partitions(InvoiceTopics.PARTITIONS)
        .replicas(InvoiceTopics.REPLICATION_FACTOR)
        .build();
  }

  public static NewTopic deliverySchedulingTopic() {
    return TopicBuilder.name(InvoiceTopics.DELIVERY_SCHEDULING)
        .partitions(InvoiceTopics.PARTITIONS)
        .replicas(InvoiceTopics.REPLICATION_FACTOR)
        .build();
  }

  public static NewTopic accountsReceivableTopic() {
    return TopicBuilder.name(InvoiceTopics.ACCOUNTS_RECEIVABLE)
        .partitions(InvoiceTopics.PARTITIONS)
        .replicas(InvoiceTopics.REPLICATION_FACTOR)
        .build();
  }
}
