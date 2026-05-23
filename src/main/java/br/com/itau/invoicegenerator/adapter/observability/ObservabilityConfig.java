package br.com.itau.invoicegenerator.adapter.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Owns the F-OBSERVABILITY beans. T1 declared the class; T2 wires the {@link CorrelationIdFilter}
 * ahead of every other filter so MDC is populated before any logger runs. T3 will attach the {@code
 * InvoiceMetricsRecorder} customizers and T4 the tracing wiring.
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
}
