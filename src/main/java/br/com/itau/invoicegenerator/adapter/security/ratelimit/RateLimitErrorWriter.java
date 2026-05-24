package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import br.com.itau.invoicegenerator.adapter.web.dto.ErrorResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * F-RATELIMIT — serialises the 429 response when {@link
 * io.github.resilience4j.ratelimiter.RateLimiter#acquirePermission()} returns {@code false}.
 *
 * <p>Filter-level rejections never reach {@code DispatcherServlet}, so
 * {@code @RestControllerAdvice} cannot intercept them — the envelope is written here directly.
 * Shape matches every other 4xx response from {@code ApiExceptionHandler}: {@code
 * {"codigo":"RATE_LIMIT_EXCEEDED","mensagem":"..."}} plus a {@code Retry-After} header carrying the
 * integer ceiling of the limiter's configured refresh period (AD-RLIM-4 — the conservative ceiling
 * is the next <em>guaranteed</em> refill).
 */
public class RateLimitErrorWriter {

  public static final String CODE = "RATE_LIMIT_EXCEEDED";
  private static final String MESSAGE =
      "Limite de requisicoes excedido. Tente novamente em alguns instantes.";

  private final ObjectMapper objectMapper;
  private final RateLimiterRegistry registry;

  public RateLimitErrorWriter(ObjectMapper objectMapper, RateLimiterRegistry registry) {
    this.objectMapper = objectMapper;
    this.registry = registry;
  }

  public void write429(HttpServletResponse response, String instanceName) throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(instanceName)));
    objectMapper.writeValue(response.getWriter(), new ErrorResponseDto(CODE, MESSAGE));
  }

  private long retryAfterSeconds(String instanceName) {
    Duration refresh =
        registry
            .find(instanceName)
            .map(rl -> rl.getRateLimiterConfig().getLimitRefreshPeriod())
            .orElseGet(() -> registry.getDefaultConfig().getLimitRefreshPeriod());
    long seconds = refresh.toSeconds();
    if (refresh.minusSeconds(seconds).toNanos() > 0) {
      seconds += 1;
    }
    return Math.max(seconds, 1);
  }
}
