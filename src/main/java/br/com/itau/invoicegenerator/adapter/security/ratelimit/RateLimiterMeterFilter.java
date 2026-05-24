package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.Set;

/**
 * F-RATELIMIT — cardinality guard for {@code resilience4j.ratelimiter.*} meters.
 *
 * <p>{@link RateLimitFilter} synthesises a {@link io.github.resilience4j.ratelimiter.RateLimiter}
 * per {@code (group, ip)} pair, which would publish one Micrometer time-series per unique IP via
 * {@code TaggedRateLimiterMetrics} — directly violating the AD-020 cardinality budget. This {@link
 * MeterFilter} denies any {@code resilience4j.ratelimiter.*} meter whose {@code name} tag is not
 * one of the three statically-named instances. Aggregate per-group signals stay queryable; per-IP
 * signals stay in logs/traces only.
 */
public class RateLimiterMeterFilter implements MeterFilter {

  private static final String METER_PREFIX = "resilience4j.ratelimiter";

  private static final Set<String> ALLOWED_NAMES =
      Set.of(
          RateLimitPolicy.GROUP_AUTH_LOGIN,
          RateLimitPolicy.GROUP_INVOICE_GENERATE,
          RateLimitPolicy.GROUP_DEFAULT);

  @Override
  public MeterFilterReply accept(Meter.Id id) {
    if (!id.getName().startsWith(METER_PREFIX)) {
      return MeterFilterReply.NEUTRAL;
    }
    String name = id.getTag("name");
    if (name != null && !ALLOWED_NAMES.contains(name)) {
      return MeterFilterReply.DENY;
    }
    return MeterFilterReply.NEUTRAL;
  }
}
