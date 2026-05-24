package br.com.itau.invoicegenerator.adapter.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.AccountsReceivablePort;
import br.com.itau.invoicegenerator.domain.port.DeliveryPort;
import br.com.itau.invoicegenerator.domain.port.InvoiceRegistrationPort;
import br.com.itau.invoicegenerator.domain.port.StockPort;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StreamUtils;

/**
 * End-to-end proof of the F-DEFECTS-PERFORMANCE Kafka pipeline. Embedded Kafka starts inside the
 * JVM; the HTTP endpoint publishes four integration events; four {@code @KafkaListener} consumers
 * receive them and invoke the (test-overridden) outbound ports.
 *
 * <p>Also asserts that the HTTP response returns far below the legacy 1480 ms latency budget — the
 * fast path no longer waits for downstream simulations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(
    partitions = InvoiceTopics.PARTITIONS,
    topics = {
      InvoiceTopics.STOCK_DEDUCTION,
      InvoiceTopics.INVOICE_REGISTRATION,
      InvoiceTopics.DELIVERY_SCHEDULING,
      InvoiceTopics.ACCOUNTS_RECEIVABLE
    })
@Import(InvoiceKafkaFlowIntegrationTest.RecordingPortsConfig.class)
@TestPropertySource(
    properties = {
      "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
      "spring.kafka.consumer.auto-offset-reset=earliest"
    })
@DirtiesContext
class InvoiceKafkaFlowIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private RecordingPorts recordingPorts;

  @Test
  void postPublishesFourEventsAndConsumersReceiveThem() throws Exception {
    String body = loadFixture("payloads/teste-pf.json");

    long startMs = System.currentTimeMillis();
    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    long httpMs = System.currentTimeMillis() - startMs;

    // The legacy synchronous request took 380+500+150+200+250 = 1480 ms (plus +5000 ms for
    // >5-item orders). With Kafka async dispatch the HTTP path only waits for publication acks.
    // 3 seconds is a generous local-CI ceiling; the production-shaped budget is closer to 500 ms.
    assertTrue(
        httpMs < 3000,
        "HTTP request must return without waiting for downstream simulations; observed "
            + httpMs
            + "ms");

    assertTrue(
        recordingPorts.stockLatch.await(15, TimeUnit.SECONDS),
        "stock deduction consumer did not call StockPort within 15s");
    assertTrue(
        recordingPorts.registrationLatch.await(15, TimeUnit.SECONDS),
        "registration consumer did not call InvoiceRegistrationPort within 15s");
    assertTrue(
        recordingPorts.deliveryLatch.await(15, TimeUnit.SECONDS),
        "delivery consumer did not call DeliveryPort within 15s");
    assertTrue(
        recordingPorts.accountsLatch.await(15, TimeUnit.SECONDS),
        "accounts receivable consumer did not call AccountsReceivablePort within 15s");
  }

  private static String loadFixture(String classpathLocation) throws Exception {
    try (var in = new ClassPathResource(classpathLocation).getInputStream()) {
      return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
    }
  }

  /**
   * Holds 4 latches, one per integration. Recording {@code @Primary} port impls count down their
   * latch when invoked, so the test can wait for end-to-end consumer dispatch without sleeping the
   * 5-second delivery simulation.
   */
  static final class RecordingPorts {
    final CountDownLatch stockLatch = new CountDownLatch(1);
    final CountDownLatch registrationLatch = new CountDownLatch(1);
    final CountDownLatch deliveryLatch = new CountDownLatch(1);
    final CountDownLatch accountsLatch = new CountDownLatch(1);
  }

  @TestConfiguration
  static class RecordingPortsConfig {

    @Bean
    RecordingPorts recordingPorts() {
      return new RecordingPorts();
    }

    @Bean
    @Primary
    StockPort recordingStockPort(RecordingPorts ports) {
      return (Invoice invoice) -> ports.stockLatch.countDown();
    }

    @Bean
    @Primary
    InvoiceRegistrationPort recordingRegistrationPort(RecordingPorts ports) {
      return (Invoice invoice) -> ports.registrationLatch.countDown();
    }

    @Bean
    @Primary
    DeliveryPort recordingDeliveryPort(RecordingPorts ports) {
      return (Invoice invoice) -> ports.deliveryLatch.countDown();
    }

    @Bean
    @Primary
    AccountsReceivablePort recordingAccountsPort(RecordingPorts ports) {
      return (Invoice invoice) -> ports.accountsLatch.countDown();
    }
  }
}
