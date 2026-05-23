package br.com.itau.invoicegenerator.adapter.observability;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bounded enumeration of every rejection {@code codigo} that {@code ApiExceptionHandler} emits.
 * Used as the cardinality-safe {@code reason} tag value on the {@code invoice.rejected} counter.
 *
 * <p>If a new rejection path appears in the domain, add it here AND in the place that throws — the
 * {@link InvoiceMetricsRecorder} rejects unknown codes at runtime, and an automated test could keep
 * this set aligned with the actual `getCode()` values returned by {@code
 * InvalidInvoiceOrderException}.
 */
public enum RejectionCode {
  UNSUPPORTED_TAX_REGIME,
  INVALID_TAX_REGIME,
  INVALID_DELIVERY_REGION;

  private static final Set<String> NAMES =
      Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

  public static RejectionCode fromCode(String code) {
    if (code == null || !NAMES.contains(code)) {
      throw new IllegalArgumentException(
          "unknown rejection code '" + code + "' — add it to RejectionCode before emitting");
    }
    return RejectionCode.valueOf(code);
  }
}
