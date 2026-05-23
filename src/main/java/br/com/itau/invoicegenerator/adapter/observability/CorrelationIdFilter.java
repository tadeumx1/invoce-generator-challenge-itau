package br.com.itau.invoicegenerator.adapter.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adopts the {@code X-Correlation-Id} request header (or generates a UUID when missing/invalid) and
 * propagates it via MDC for the duration of the request. Echoes the value back on the response so
 * callers can correlate their side.
 *
 * <p>Header value must match {@code ^[A-Za-z0-9_-]{1,128}$}; anything else is discarded with a WARN
 * and replaced by a fresh UUID. This keeps logs grep-able and prevents the header from being used
 * to inject characters into structured log fields.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER_NAME = "X-Correlation-Id";
  public static final String MDC_KEY = InvoiceKafkaHeaders.CORRELATION_ID;

  private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
  private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String raw = request.getHeader(HEADER_NAME);
    String correlationId;
    if (raw != null && ALLOWED.matcher(raw).matches()) {
      correlationId = raw;
    } else {
      if (raw != null) {
        log.warn("rejected malformed correlation id, generating fresh one");
      }
      correlationId = UUID.randomUUID().toString();
    }
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER_NAME, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
