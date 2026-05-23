package br.com.itau.invoicegenerator.testsupport;

import br.com.itau.invoicegenerator.domain.port.InvoiceSideEffectDispatcher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Provides a no-op {@link InvoiceSideEffectDispatcher} for {@code @SpringBootTest} contexts that
 * exclude {@code KafkaAutoConfiguration}. F-DEFECTS-PERFORMANCE T2/T3 add EmbeddedKafka-driven
 * tests that exercise the real Kafka publish/consume path.
 */
@TestConfiguration
public class NoOpKafkaTestConfig {

  @Bean
  @Primary
  InvoiceSideEffectDispatcher noOpInvoiceSideEffectDispatcher() {
    return ignored -> {};
  }
}
