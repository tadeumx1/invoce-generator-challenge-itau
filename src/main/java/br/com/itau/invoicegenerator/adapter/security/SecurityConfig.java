package br.com.itau.invoicegenerator.adapter.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * F-AUTH T1 — security foundation. Wires the HS256 {@link JwtEncoder}/{@link JwtDecoder} pair from
 * the same shared secret, a BCrypt {@link PasswordEncoder}, and a permissive filter chain.
 *
 * <p>T3 flips the filter chain from "permit all" to "require SCOPE_invoice:write on the invoice
 * endpoints". Keeping that change in its own commit makes the diff for "Spring Security on the
 * classpath" separately reviewable from "endpoints actually locked down".
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
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    // T1: permit everything so existing tests still pass with Spring Security on the classpath.
    // T3 replaces this with the real authorize-requests block + oauth2ResourceServer wiring.
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .build();
  }
}
