package br.com.itau.invoicegenerator.adapter.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Single source of truth for the Kafka-backed invoice messaging beans. The whole configuration is
 * gated on the explicit property {@code app.messaging.kafka.enabled=true} (set in {@code
 * application.properties}). Tests that exclude Kafka set the property to {@code false} and rely on
 * a {@code @Primary} no-op dispatcher.
 *
 * <p>Using an explicit property instead of {@code @ConditionalOnBean(KafkaTemplate.class)} avoids
 * the well-known timing pitfall where Spring evaluates conditions on user {@code @Configuration}
 * classes before auto-configurations contribute their beans.
 */
@Configuration
@ConditionalOnProperty(name = "app.messaging.kafka.enabled", havingValue = "true")
public class KafkaMessagingConfig {

  @Bean
  public IntegrationEventPublisher integrationEventPublisher(
      KafkaTemplate<String, IntegrationEvent> kafkaTemplate) {
    return new IntegrationEventPublisher(kafkaTemplate);
  }

  @Bean
  public KafkaInvoiceSideEffectDispatcher kafkaInvoiceSideEffectDispatcher(
      IntegrationEventPublisher publisher) {
    return new KafkaInvoiceSideEffectDispatcher(publisher);
  }

  @Bean
  public NewTopic stockDeductionTopic() {
    return KafkaTopicsConfig.stockDeductionTopic();
  }

  @Bean
  public NewTopic invoiceRegistrationTopic() {
    return KafkaTopicsConfig.invoiceRegistrationTopic();
  }

  @Bean
  public NewTopic deliverySchedulingTopic() {
    return KafkaTopicsConfig.deliverySchedulingTopic();
  }

  @Bean
  public NewTopic accountsReceivableTopic() {
    return KafkaTopicsConfig.accountsReceivableTopic();
  }
}
