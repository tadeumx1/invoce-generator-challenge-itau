package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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

  @Bean
  public RateLimitFilter rateLimitFilter(
      RateLimitPolicy policy,
      RateLimiterRegistry registry,
      ClientIpResolver ipResolver,
      RateLimitErrorWriter errorWriter) {
    return new RateLimitFilter(policy, registry, ipResolver, errorWriter);
  }

  /**
   * Suppress Spring Boot's auto-registration of {@link RateLimitFilter} as a top-level servlet
   * filter — the {@code SecurityFilterChain} owns its placement via {@code addFilterBefore}.
   * Without this, the filter would run twice per request.
   */
  @Bean
  public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
      RateLimitFilter filter) {
    FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  /**
   * Install the {@link RateLimiterMeterFilter} cardinality guard on every Micrometer registry that
   * appears in the context — keeps per-(group, ip) synthetic instance names from publishing meters
   * (AD-RLIM-2 + AD-020).
   */
  @Bean
  public MeterRegistryCustomizer<io.micrometer.core.instrument.MeterRegistry>
      rateLimiterMeterCardinalityGuard() {
    return registry -> registry.config().meterFilter(new RateLimiterMeterFilter());
  }
}
