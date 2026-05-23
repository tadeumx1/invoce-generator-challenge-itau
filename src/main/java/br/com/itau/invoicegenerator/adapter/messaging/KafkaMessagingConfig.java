package br.com.itau.invoicegenerator.adapter.messaging;

import br.com.itau.invoicegenerator.adapter.integration.delivery.DeliverySchedulingConsumer;
import br.com.itau.invoicegenerator.adapter.integration.finance.AccountsReceivableConsumer;
import br.com.itau.invoicegenerator.adapter.integration.registration.InvoiceRegistrationConsumer;
import br.com.itau.invoicegenerator.adapter.integration.stock.StockDeductionConsumer;
import br.com.itau.invoicegenerator.adapter.observability.InvoiceMetricsRecorder;
import br.com.itau.invoicegenerator.adapter.observability.MdcRestoringRecordInterceptor;
import br.com.itau.invoicegenerator.adapter.observability.SideEffectTimingConsumerListener;
import br.com.itau.invoicegenerator.domain.port.AccountsReceivablePort;
import br.com.itau.invoicegenerator.domain.port.DeliveryPort;
import br.com.itau.invoicegenerator.domain.port.InvoiceRegistrationPort;
import br.com.itau.invoicegenerator.domain.port.StockPort;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CompositeRecordInterceptor;

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
      KafkaTemplate<String, IntegrationEvent> kafkaTemplate,
      InvoiceMetricsRecorder metricsRecorder) {
    return new IntegrationEventPublisher(kafkaTemplate, metricsRecorder);
  }

  /**
   * Overrides Spring Boot's default {@code kafkaListenerContainerFactory} so the observability
   * interceptors (MDC restoration + side-effect timing) run on every consumer record. Using the
   * Boot-provided {@link ConcurrentKafkaListenerContainerFactoryConfigurer} keeps Boot's
   * auto-configured consumer settings intact.
   */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
      ConsumerFactory<Object, Object> consumerFactory,
      MdcRestoringRecordInterceptor mdcInterceptor,
      SideEffectTimingConsumerListener timingInterceptor) {
    ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    configurer.configure(factory, consumerFactory);
    @SuppressWarnings({"unchecked", "rawtypes"})
    CompositeRecordInterceptor<Object, Object> composite =
        new CompositeRecordInterceptor<>(
            (org.springframework.kafka.listener.RecordInterceptor) mdcInterceptor,
            (org.springframework.kafka.listener.RecordInterceptor) timingInterceptor);
    factory.setRecordInterceptor(composite);
    return factory;
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

  @Bean
  public IdempotencyStore idempotencyStore() {
    return new IdempotencyStore();
  }

  @Bean
  public StockDeductionConsumer stockDeductionConsumer(
      StockPort stockPort, IdempotencyStore idempotencyStore) {
    return new StockDeductionConsumer(stockPort, idempotencyStore);
  }

  @Bean
  public InvoiceRegistrationConsumer invoiceRegistrationConsumer(
      InvoiceRegistrationPort registrationPort, IdempotencyStore idempotencyStore) {
    return new InvoiceRegistrationConsumer(registrationPort, idempotencyStore);
  }

  @Bean
  public DeliverySchedulingConsumer deliverySchedulingConsumer(
      DeliveryPort deliveryPort, IdempotencyStore idempotencyStore) {
    return new DeliverySchedulingConsumer(deliveryPort, idempotencyStore);
  }

  @Bean
  public AccountsReceivableConsumer accountsReceivableConsumer(
      AccountsReceivablePort accountsReceivablePort, IdempotencyStore idempotencyStore) {
    return new AccountsReceivableConsumer(accountsReceivablePort, idempotencyStore);
  }
}
