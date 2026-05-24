package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RateLimitPolicyTest {

  private final RateLimitPolicy policy = new RateLimitPolicy();

  @Test
  void loginPostMapsToAuthLoginGroup() {
    assertThat(policy.lookup("POST", "/api/auth/login"))
        .isEqualTo(RateLimitPolicy.GROUP_AUTH_LOGIN);
  }

  @Test
  void canonicalInvoiceEndpointMapsToInvoiceGenerateGroup() {
    assertThat(policy.lookup("POST", "/api/orders/generate-invoice"))
        .isEqualTo(RateLimitPolicy.GROUP_INVOICE_GENERATE);
  }

  @Test
  void legacyInvoiceAliasSharesInvoiceGenerateBucket() {
    assertThat(policy.lookup("POST", "/api/pedido/gerarNotaFiscal"))
        .isEqualTo(RateLimitPolicy.GROUP_INVOICE_GENERATE);
  }

  @Test
  void actuatorHealthIsExempt() {
    assertThat(policy.lookup("GET", "/actuator/health")).isNull();
  }

  @Test
  void actuatorHealthSubPathIsExempt() {
    assertThat(policy.lookup("GET", "/actuator/health/liveness")).isNull();
  }

  @Test
  void actuatorPrometheusIsExempt() {
    assertThat(policy.lookup("GET", "/actuator/prometheus")).isNull();
  }

  @Test
  void actuatorInfoIsExempt() {
    assertThat(policy.lookup("GET", "/actuator/info")).isNull();
  }

  @Test
  void unknownApiPathFallsThroughToDefault() {
    assertThat(policy.lookup("GET", "/api/some/future/endpoint"))
        .isEqualTo(RateLimitPolicy.GROUP_DEFAULT);
  }

  @Test
  void nonApiNonActuatorPathIsExempt() {
    assertThat(policy.lookup("GET", "/favicon.ico")).isNull();
  }

  @Test
  void methodMismatchOnSpecificRuleFallsThrough() {
    // GET /api/auth/login does not match the POST-only rule; falls through to /api/** default.
    assertThat(policy.lookup("GET", "/api/auth/login")).isEqualTo(RateLimitPolicy.GROUP_DEFAULT);
  }

  @Test
  void nullPathReturnsNull() {
    assertThat(policy.lookup("GET", null)).isNull();
  }

  @Test
  void methodMatchIsCaseInsensitive() {
    assertThat(policy.lookup("post", "/api/auth/login"))
        .isEqualTo(RateLimitPolicy.GROUP_AUTH_LOGIN);
  }
}
