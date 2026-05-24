package br.com.itau.invoicegenerator.adapter.security;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.itau.invoicegenerator.testsupport.NoOpKafkaTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F-AUTH T5 — covers {@link br.com.itau.invoicegenerator.adapter.security.login.AuthController}.
 * Validates the OAuth2-shaped response on success, the {@code {codigo:INVALID_CREDENTIALS,
 * mensagem}} envelope on bad password / unknown user, and the {@code INVALID_LOGIN_PAYLOAD}
 * envelope on missing fields.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NoOpKafkaTestConfig.class)
@TestPropertySource(
    properties = {
      "app.messaging.kafka.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
      // F-RATELIMIT T2 — six login attempts here would trip the prod limit of 5/min. Raise the
      // ceiling for this class so the suite never falsely throttles. RateLimitIntegrationTest is
      // the only class that exercises the throttling contract directly.
      "resilience4j.ratelimiter.instances.auth-login.limit-for-period=10000"
    })
class AuthControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void loginWithValidDemoUserReturnsAccessToken() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.access_token").value(notNullValue()))
        .andExpect(
            jsonPath("$.access_token")
                .value(
                    org.hamcrest.Matchers.matchesPattern(
                        "[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")))
        .andExpect(jsonPath("$.token_type").value(equalTo("Bearer")))
        .andExpect(jsonPath("$.expires_in").value(greaterThan(0)))
        .andExpect(jsonPath("$.scope").value(equalTo("invoice:write")));
  }

  @Test
  void loginWithAdminUserReturnsBothScopes() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope").value(equalTo("invoice:write invoice:admin")));
  }

  @Test
  void loginWithWrongPasswordReturns401WithInvalidCredentials() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"demo\",\"password\":\"WRONG\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.codigo").value(equalTo("INVALID_CREDENTIALS")))
        .andExpect(jsonPath("$.mensagem").value(notNullValue()));
  }

  @Test
  void loginWithUnknownUserReturns401WithInvalidCredentials() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ghost\",\"password\":\"whatever\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.codigo").value(equalTo("INVALID_CREDENTIALS")));
  }

  @Test
  void loginWithMissingPasswordReturns400WithInvalidLoginPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"demo\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.codigo").value(equalTo("INVALID_LOGIN_PAYLOAD")));
  }

  @Test
  void loginWithBlankUsernameReturns400WithInvalidLoginPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"password\":\"demo123\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.codigo").value(equalTo("INVALID_LOGIN_PAYLOAD")));
  }
}
