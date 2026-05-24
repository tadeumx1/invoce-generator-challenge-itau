package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import java.util.List;
import org.springframework.util.AntPathMatcher;

/**
 * F-RATELIMIT — single source of truth mapping an HTTP {@code (method, path)} to a rate-limit
 * instance name. Returns {@code null} when the request is exempt (every {@code /actuator/**}
 * request, every {@code OPTIONS} preflight, and any path outside {@code /api/**}).
 *
 * <p>Rule order matters: the actuator exemption is evaluated before the {@code /api/**} catch-all
 * so a future actuator path under {@code /api/...} would not accidentally fall through to the
 * default bucket. New {@code /api/**} endpoints inherit the {@code default} group automatically.
 */
public class RateLimitPolicy {

  public static final String GROUP_AUTH_LOGIN = "auth-login";
  public static final String GROUP_INVOICE_GENERATE = "invoice-generate";
  public static final String GROUP_DEFAULT = "default";

  private static final String METHOD_WILDCARD = "*";

  private static final List<Rule> RULES =
      List.of(
          new Rule("POST", "/api/auth/login", GROUP_AUTH_LOGIN),
          new Rule("POST", "/api/orders/generate-invoice", GROUP_INVOICE_GENERATE),
          new Rule("POST", "/api/pedido/gerarNotaFiscal", GROUP_INVOICE_GENERATE),
          new Rule(METHOD_WILDCARD, "/actuator/**", null),
          new Rule(METHOD_WILDCARD, "/api/**", GROUP_DEFAULT));

  private final AntPathMatcher matcher = new AntPathMatcher();

  public String lookup(String method, String path) {
    if (path == null) {
      return null;
    }
    for (Rule rule : RULES) {
      if (!METHOD_WILDCARD.equals(rule.method) && !rule.method.equalsIgnoreCase(method)) {
        continue;
      }
      if (matcher.match(rule.pattern, path)) {
        return rule.instance;
      }
    }
    return null;
  }

  private record Rule(String method, String pattern, String instance) {}
}
