package br.com.itau.invoicegenerator.adapter.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.itau.invoicegenerator.testsupport.NoOpKafkaTestConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StreamUtils;

/**
 * F-OBSERVABILITY T3 wiring check — proves the business counters are emitted from real HTTP traffic
 * and become visible on the {@code /actuator/prometheus} endpoint. Without this test, the {@code
 * InvoiceMetricsRecorder} bean would compile and unit-test green yet never increment in production
 * (which is exactly what would have shipped if T3 had not been audited).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Import(NoOpKafkaTestConfig.class)
@TestPropertySource(
    properties = {
      "app.messaging.kafka.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
class MetricsIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void successfulPostIncrementsInvoiceGeneratedCounter() throws Exception {
    String body = loadFixture("payloads/teste-pf.json");

    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    String prometheus = scrapePrometheus();
    assertTrue(
        prometheus.contains("invoice_generated_total{"),
        "expected invoice_generated_total on /actuator/prometheus, got:\n" + prometheus);
    assertTrue(
        prometheus.contains("person_type=\"FISICA\""),
        "expected person_type tag from the FISICA payload");
    assertTrue(
        prometheus.contains("region=\"SUDESTE\""), "expected region tag from the SUDESTE payload");
  }

  @Test
  void rejectedPostIncrementsInvoiceRejectedCounter() throws Exception {
    String body =
        loadFixture("payloads/teste-pj-simples.json")
            .replace(
                "\"regime_tributacao\": \"SIMPLES_NACIONAL\"", "\"regime_tributacao\": \"OUTROS\"");

    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());

    String prometheus = scrapePrometheus();
    assertTrue(
        prometheus
            .lines()
            .anyMatch(
                line ->
                    line.startsWith("invoice_rejected_total{")
                        && line.contains("reason=\"UNSUPPORTED_TAX_REGIME\"")),
        "expected invoice_rejected_total line tagged reason=UNSUPPORTED_TAX_REGIME, got:\n"
            + prometheus);
  }

  @Test
  void httpServerRequestsHistogramExposesSloBuckets() throws Exception {
    String body = loadFixture("payloads/teste-pf.json");

    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    String prometheus = scrapePrometheus();
    assertTrue(
        prometheus.contains("uri=\"/api/orders/generate-invoice\""),
        "expected an http_server_requests entry for the invoice endpoint");
    assertTrue(
        prometheus.contains("le=\"0.3\""),
        "expected the 300 ms SLO bucket on http_server_requests");
    assertTrue(
        prometheus.contains("le=\"0.8\""),
        "expected the 800 ms SLO bucket on http_server_requests");
    assertTrue(
        prometheus.contains("le=\"2.0\""), "expected the 2 s SLO bucket on http_server_requests");
  }

  private String scrapePrometheus() throws Exception {
    return mockMvc
        .perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static String loadFixture(String classpathLocation) throws Exception {
    try (var in = new ClassPathResource(classpathLocation).getInputStream()) {
      return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
    }
  }
}
