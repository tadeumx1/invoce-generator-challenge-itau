package br.com.itau.invoicegenerator.adapter.observability;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Owns the F-OBSERVABILITY beans: the {@link CorrelationIdFilter} (T2) registered ahead of every
 * other filter, the {@link InvoiceMetricsRecorder} (T3), and the {@link UseCaseObservation} + Kafka
 * MDC/timing interceptors (T4). The Kafka listener container factory in {@link
 * br.com.itau.invoicegenerator.adapter.messaging.KafkaMessagingConfig} wires both interceptors so
 * the management plane stays close to the producer/consumer beans.
 */
@Configuration
public class ObservabilityConfig {

  @Bean
  public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
    FilterRegistrationBean<CorrelationIdFilter> registration =
        new FilterRegistrationBean<>(new CorrelationIdFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    return registration;
  }

  @Bean
  public MdcRestoringRecordInterceptor mdcRestoringRecordInterceptor() {
    return new MdcRestoringRecordInterceptor();
  }

  @Bean
  public InvoiceMetricsRecorder invoiceMetricsRecorder(MeterRegistry registry) {
    return new InvoiceMetricsRecorder(registry);
  }

  @Bean
  public UseCaseObservation useCaseObservation(
      GenerateInvoiceUseCase generateInvoiceUseCase, ObservationRegistry observationRegistry) {
    return new UseCaseObservation(generateInvoiceUseCase, observationRegistry);
  }

  @Bean
  public SideEffectTimingConsumerListener sideEffectTimingConsumerListener(
      InvoiceMetricsRecorder invoiceMetricsRecorder) {
    return new SideEffectTimingConsumerListener(invoiceMetricsRecorder);
  }
}
