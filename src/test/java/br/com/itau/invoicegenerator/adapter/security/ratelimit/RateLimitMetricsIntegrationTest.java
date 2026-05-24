package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.itau.invoicegenerator.testsupport.NoOpKafkaTestConfig;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F-RATELIMIT T3 — proves the cardinality guard works end-to-end. Test 1: after one auth-login hit,
 * the {@code resilience4j_ratelimiter_available_permissions{name="auth-login"}} meter is scrapable
 * on {@code /actuator/prometheus}. Test 2: after hits from many synthetic IPs, the scrape still
 * contains <em>only</em> the three statically-named instances — no per-IP synthetic names leak into
 * the meter set (RLIM-26, AD-020).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NoOpKafkaTestConfig.class)
@TestPropertySource(
    properties = {
      "app.messaging.kafka.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
      "resilience4j.ratelimiter.instances.auth-login.limit-for-period=10000",
      "resilience4j.ratelimiter.instances.invoice-generate.limit-for-period=10000"
    })
class RateLimitMetricsIntegrationTest {

  private static final Pattern PER_IP_LEAK =
      Pattern.compile("resilience4j_ratelimiter_[^{]+\\{[^}]*name=\"[^\"]*:[^\"]*\"");

  @Autowired private MockMvc mockMvc;

  @Test
  void authLoginMeterAppearsOnScrapeAfterFirstHit() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .header("X-Forwarded-For", "10.0.0.50")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
        .andReturn();

    String scrape =
        mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();

    assertThat(scrape)
        .containsPattern(
            "resilience4j_ratelimiter_available_permissions\\{[^}]*name=\"auth-login\"");
  }

  @Test
  void perIpSyntheticInstanceNamesAreRejectedFromMicrometer() throws Exception {
    for (String ip : List.of("10.0.0.51", "10.0.0.52", "10.0.0.53", "10.0.0.54")) {
      mockMvc
          .perform(
              post("/api/auth/login")
                  .header("X-Forwarded-For", ip)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
          .andReturn();
    }

    String scrape =
        mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();

    assertThat(PER_IP_LEAK.matcher(scrape).find())
        .as(
            "no resilience4j_ratelimiter_* meter should carry a name tag with ':' (per-IP "
                + "synthetic instance leak); scrape excerpt: "
                + scrape
                    .lines()
                    .filter(line -> line.contains("resilience4j_ratelimiter"))
                    .limit(10)
                    .toList())
        .isFalse();
  }
}
