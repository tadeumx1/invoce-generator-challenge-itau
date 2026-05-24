package br.com.itau.invoicegenerator.adapter.security;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.itau.invoicegenerator.testsupport.JwtTestSupport;
import br.com.itau.invoicegenerator.testsupport.NoOpKafkaTestConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StreamUtils;

/**
 * F-AUTH T5 — covers the SecurityFilterChain end-to-end against {@code POST
 * /api/orders/generate-invoice}. Exercises the no-token / malformed / expired / valid-but-no-scope
 * / valid-with-scope paths plus a sanity check that actuator and the login endpoint stay public.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({NoOpKafkaTestConfig.class, JwtTestSupport.class})
@TestPropertySource(
    properties = {
      "app.messaging.kafka.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
class SecurityIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTestSupport jwt;

  @Test
  void invoiceEndpointWithoutTokenReturns401() throws Exception {
    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loadFixture("payloads/teste-pf.json")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.codigo").value(equalTo("UNAUTHORIZED")));
  }

  @Test
  void invoiceEndpointWithMalformedTokenReturns401() throws Exception {
    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loadFixture("payloads/teste-pf.json")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.codigo").value(equalTo("UNAUTHORIZED")));
  }

  @Test
  void invoiceEndpointWithExpiredTokenReturns401() throws Exception {
    String expired = jwt.expiredTokenFor("demo", "invoice:write");
    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loadFixture("payloads/teste-pf.json")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.codigo").value(equalTo("UNAUTHORIZED")));
  }

  @Test
  void invoiceEndpointWithValidTokenMissingScopeReturns403() throws Exception {
    String tokenWithoutScope = jwt.tokenFor("demo", "invoice:read");
    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutScope)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loadFixture("payloads/teste-pf.json")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.codigo").value(equalTo("FORBIDDEN")));
  }

  @Test
  void invoiceEndpointWithValidTokenAndScopeReturns200() throws Exception {
    String token = jwt.tokenFor("demo", "invoice:write");
    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loadFixture("payloads/teste-pf.json")))
        .andExpect(status().isOk());
  }

  @Test
  void legacyAliasIsAlsoProtected() throws Exception {
    mockMvc
        .perform(
            post("/api/pedido/gerarNotaFiscal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loadFixture("payloads/teste-pf.json")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void actuatorHealthIsPublic() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void actuatorPrometheusIsPublic() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
  }

  @Test
  void loginEndpointIsPublic() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
        .andExpect(status().isOk());
  }

  private static String loadFixture(String classpathLocation) throws Exception {
    try (var in = new ClassPathResource(classpathLocation).getInputStream()) {
      return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
    }
  }
}
