package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * F-RATELIMIT — per-IP rate-limit filter. Installed via {@code HttpSecurity.addFilterBefore(...,
 * BearerTokenAuthenticationFilter.class)} so abuse traffic is rejected before any JWT validation
 * cost is paid (AD-RLIM-1).
 *
 * <p>Per-IP isolation is achieved by synthesising a unique {@code (group, ip)} {@link RateLimiter}
 * via {@link RateLimiterRegistry#rateLimiter(String, RateLimiterConfig)}; lookups are thread-safe
 * {@code ConcurrentHashMap} reads (AD-RLIM-2). The aggregate per-group meters stay bounded because
 * {@code RateLimiterMeterFilter} (T3) keeps the synthetic per-IP names off the global registry —
 * AD-020 cardinality budget preserved.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

  private final RateLimitPolicy policy;
  private final RateLimiterRegistry registry;
  private final ClientIpResolver ipResolver;
  private final RateLimitErrorWriter errorWriter;

  public RateLimitFilter(
      RateLimitPolicy policy,
      RateLimiterRegistry registry,
      ClientIpResolver ipResolver,
      RateLimitErrorWriter errorWriter) {
    this.policy = policy;
    this.registry = registry;
    this.ipResolver = ipResolver;
    this.errorWriter = errorWriter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }

    String group = policy.lookup(request.getMethod(), request.getRequestURI());
    if (group == null) {
      chain.doFilter(request, response);
      return;
    }

    Optional<RateLimiter> prototype = registry.find(group);
    if (prototype.isEmpty()) {
      LOG.warn(
          "rate-limit group '{}' has no resilience4j.ratelimiter.instances configuration; "
              + "request passes through unlimited",
          group);
      chain.doFilter(request, response);
      return;
    }

    String ip = ipResolver.resolve(request);
    RateLimiter limiter =
        registry.rateLimiter(group + ":" + ip, prototype.get().getRateLimiterConfig());

    if (limiter.acquirePermission()) {
      chain.doFilter(request, response);
    } else {
      LOG.debug("rate-limit tripped: group={} ip={}", group, ip);
      errorWriter.write429(response, group);
    }
  }
}
