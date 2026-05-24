package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * F-RATELIMIT — extracts the client IP for per-IP bucket keying.
 *
 * <p>Prefers the leftmost hop of {@code X-Forwarded-For} (the de-facto convention where the
 * leftmost entry is the original client). Falls back to {@link HttpServletRequest#getRemoteAddr()}
 * when the header is absent, empty, or whitespace-only. Returns the literal {@code "unknown"}
 * sentinel when both sources are degenerate so the filter never throws — the safe failure mode is
 * over-throttle (all "unknown" callers share one bucket), never under-throttle.
 */
public class ClientIpResolver {

  public static final String UNKNOWN = "unknown";
  private static final String XFF_HEADER = "X-Forwarded-For";

  public String resolve(HttpServletRequest request) {
    String forwarded = request.getHeader(XFF_HEADER);
    if (forwarded != null) {
      int comma = forwarded.indexOf(',');
      String firstHop = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
      if (!firstHop.isEmpty()) {
        return firstHop;
      }
    }
    String remote = request.getRemoteAddr();
    if (remote == null || remote.isBlank()) {
      return UNKNOWN;
    }
    return remote;
  }
}
