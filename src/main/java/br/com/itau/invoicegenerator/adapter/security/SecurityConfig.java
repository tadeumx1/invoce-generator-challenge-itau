package br.com.itau.invoicegenerator.adapter.security;

import br.com.itau.invoicegenerator.adapter.security.error.ApiBearerAccessDeniedHandler;
import br.com.itau.invoicegenerator.adapter.security.error.ApiBearerAuthenticationEntryPoint;
import br.com.itau.invoicegenerator.adapter.security.ratelimit.RateLimitFilter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * F-AUTH — security foundation + filter chain.
 *
 * <p>T1 introduced the JWT beans and a permissive filter chain. T3 (this revision) flips the chain
 * to enforcing: actuator health/info/prometheus and {@code POST /api/auth/login} are public;
 * everything else requires a valid JWT; the invoice endpoints additionally require {@code
 * SCOPE_invoice:write}. Custom 401/403 handlers preserve the {@code {codigo, mensagem}} envelope
 * used by the rest of the API.
 */
@Configuration
@EnableConfigurationProperties(ApiSecurityProperties.class)
public class SecurityConfig {

  private final ApiSecurityProperties properties;

  public SecurityConfig(ApiSecurityProperties properties) {
    this.properties = properties;
  }

  @Bean
  public SecretKey jwtSigningKey() {
    byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
    if (secretBytes.length < 32) {
      throw new IllegalStateException(
          "app.security.jwt.secret must be at least 32 bytes (256 bits) for HS256; got "
              + secretBytes.length);
    }
    return new SecretKeySpec(secretBytes, "HmacSHA256");
  }

  @Bean
  public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
  }

  @Bean
  public JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
    return NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      ApiBearerAuthenticationEntryPoint authenticationEntryPoint,
      ApiBearerAccessDeniedHandler accessDeniedHandler,
      RateLimitFilter rateLimitFilter)
      throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)
        .exceptionHandling(
            eh ->
                eh.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(HttpMethod.POST, "/api/auth/login")
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus")
                    .permitAll()
                    // F-API-DOCS — OpenAPI 3 document + Swagger UI are reviewer-facing surfaces.
                    // F-RATELIMIT already does not throttle them (they live outside /api/**).
                    .requestMatchers(
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                        "/swagger-ui",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/orders/generate-invoice",
                        "/api/pedido/gerarNotaFiscal")
                    .hasAuthority("SCOPE_invoice:write")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> j.decoder(jwtDecoder))
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .build();
  }
}
