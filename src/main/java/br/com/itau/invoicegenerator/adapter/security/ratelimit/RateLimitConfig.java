package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * F-RATELIMIT — bean wiring for the rate-limit components.
 *
 * <p>T1 declares the helpers ({@link ClientIpResolver}, {@link RateLimitPolicy}, {@link
 * RateLimitErrorWriter}); T2 adds the {@code RateLimitFilter} and its registration with the
 * existing {@code SecurityFilterChain}; T3 adds the {@code MeterRegistryCustomizer} that installs
 * the cardinality guard (per AD-RLIM-2 / AD-020).
 */
@Configuration
public class RateLimitConfig {

  @Bean
  public ClientIpResolver clientIpResolver() {
    return new ClientIpResolver();
  }

  @Bean
  public RateLimitPolicy rateLimitPolicy() {
    return new RateLimitPolicy();
  }

  @Bean
  public RateLimitErrorWriter rateLimitErrorWriter(
      ObjectMapper objectMapper, RateLimiterRegistry registry) {
    return new RateLimitErrorWriter(objectMapper, registry);
  }
}
