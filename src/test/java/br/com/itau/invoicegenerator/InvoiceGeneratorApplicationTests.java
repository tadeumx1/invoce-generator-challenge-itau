package br.com.itau.invoicegenerator;

import br.com.itau.invoicegenerator.testsupport.NoOpKafkaTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test that the Spring context loads. Kafka auto-configuration is excluded so this test does
 * not block on KafkaAdmin metadata fetches when no broker is reachable; EmbeddedKafka-driven tests
 * under F-DEFECTS-PERFORMANCE T2/T3 cover the full Kafka wiring.
 */
@SpringBootTest
@Import(NoOpKafkaTestConfig.class)
@TestPropertySource(
    properties = {
      "app.messaging.kafka.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
class InvoiceGeneratorApplicationTests {

  @Test
  void contextLoads() {}
}
