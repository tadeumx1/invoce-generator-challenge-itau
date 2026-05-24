package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.itau.invoicegenerator.testsupport.NoOpKafkaTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * F-RATELIMIT T2 — covers the rate-limit filter end-to-end through the real {@code
 * SecurityFilterChain}. Each method targets a <em>distinct synthetic IP</em> in {@code
 * X-Forwarded-For} so bucket state cannot leak between tests (AD-RLIM-5).
 *
 * <p>Limits are shrunk via {@code @TestPropertySource} so the trip is observable in a handful of
 * requests rather than the prod ceilings of 5/30/60 per minute.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NoOpKafkaTestConfig.class)
@TestPropertySource(
    properties = {
      "app.messaging.kafka.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
      "resilience4j.ratelimiter.instances.auth-login.limit-for-period=3",
      "resilience4j.ratelimiter.instances.auth-login.limit-refresh-period=60s",
      "resilience4j.ratelimiter.instances.invoice-generate.limit-for-period=4",
      "resilience4j.ratelimiter.instances.invoice-generate.limit-refresh-period=60s"
    })
class RateLimitIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void loginTripsOnFourthRequestFromSameIpAndCarriesEnvelopePlusRetryAfter() throws Exception {
    String ip = "10.0.0.1";
    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(
              post("/api/auth/login")
                  .header("X-Forwarded-For", ip)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
          .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
    }

    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(429);
    assertThat(result.getResponse().getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("codigo").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
    assertThat(body.get("mensagem").asText()).isNotBlank();
    String retryAfter = result.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
    assertThat(retryAfter).isNotNull();
    assertThat(Integer.parseInt(retryAfter)).isGreaterThan(0);
  }

  @Test
  void perIpIsolationKeepsOtherClientsUnaffected() throws Exception {
    String noisyIp = "10.0.0.2";
    String quietIp = "10.0.0.3";

    for (int i = 0; i < 4; i++) {
      mockMvc.perform(
          post("/api/auth/login")
              .header("X-Forwarded-For", noisyIp)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"username\":\"demo\",\"password\":\"demo123\"}"));
    }
    MvcResult tripped =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .header("X-Forwarded-For", noisyIp)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
            .andReturn();
    assertThat(tripped.getResponse().getStatus()).isEqualTo(429);

    MvcResult quietRequest =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .header("X-Forwarded-For", quietIp)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
            .andReturn();
    assertThat(quietRequest.getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void actuatorHealthIsNeverThrottled() throws Exception {
    String ip = "10.0.0.4";
    for (int i = 0; i < 30; i++) {
      MvcResult r =
          mockMvc.perform(get("/actuator/health").header("X-Forwarded-For", ip)).andReturn();
      assertThat(r.getResponse().getStatus()).isEqualTo(200);
    }
  }

  @Test
  void invoiceCanonicalAndLegacyAliasShareTheSameBucket() throws Exception {
    String ip = "10.0.0.5";
    String emptyBody = "{}";

    for (int i = 0; i < 4; i++) {
      mockMvc.perform(
          post("/api/orders/generate-invoice")
              .header("X-Forwarded-For", ip)
              .contentType(MediaType.APPLICATION_JSON)
              .content(emptyBody));
    }

    MvcResult viaAlias =
        mockMvc
            .perform(
                post("/api/pedido/gerarNotaFiscal")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(emptyBody))
            .andReturn();
    assertThat(viaAlias.getResponse().getStatus()).isEqualTo(429);
    JsonNode body = objectMapper.readTree(viaAlias.getResponse().getContentAsString());
    assertThat(body.get("codigo").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void optionsPreflightDoesNotConsumePermits() throws Exception {
    String ip = "10.0.0.6";
    for (int i = 0; i < 10; i++) {
      mockMvc.perform(options("/api/auth/login").header("X-Forwarded-For", ip));
    }
    MvcResult afterPreflight =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
            .andReturn();
    assertThat(afterPreflight.getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void malformedXForwardedForFallsBackWithoutThrowing() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .header("X-Forwarded-For", "   ")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isIn(200, 429);
  }
}
