package br.com.itau.invoicegenerator.adapter.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.itau.invoicegenerator.testsupport.NoOpKafkaTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F-API-DOCS T3 — proves the OpenAPI document is reachable anonymously, declares the {@code
 * bearer-jwt} HTTP security scheme that mirrors F-AUTH, and surfaces the three productive
 * endpoints. Closes the AD-029 "registered ≠ exercised" failure mode for the docs surface.
 *
 * <p>Mirrors {@code SecurityIntegrationTest} — full production filter chain (no {@code
 * addFilters=false}), MockMvc, Kafka disabled via {@link NoOpKafkaTestConfig}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NoOpKafkaTestConfig.class)
@TestPropertySource(
    properties = {
      "app.messaging.kafka.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
class OpenApiDocsIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void apiDocsReachableAnonymously() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.openapi", startsWith("3.")))
        .andExpect(jsonPath("$.info.title").value("Invoice Generator API"));
  }

  @Test
  void apiDocsDeclaresBearerJwtSecurityScheme() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearer-jwt.type").value("http"))
        .andExpect(jsonPath("$.components.securitySchemes.bearer-jwt.scheme").value("bearer"))
        .andExpect(jsonPath("$.components.securitySchemes.bearer-jwt.bearerFormat").value("JWT"));
  }

  @Test
  void apiDocsSurfacesThreeProductiveEndpoints() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("/api/auth/login"),
                        containsString("/api/orders/generate-invoice"),
                        containsString("/api/pedido/gerarNotaFiscal"))));
  }

  @Test
  void swaggerUiReachableAnonymously() throws Exception {
    // Springdoc may serve /swagger-ui.html as a 302 redirect to /swagger-ui/index.html OR as 200
    // directly. Both are valid; what matters is the response is not blocked by Spring Security.
    int status = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
    if (status != 200 && status != 302) {
      throw new AssertionError(
          "expected 200 or 302 for /swagger-ui.html anonymously, got " + status);
    }
  }
}
