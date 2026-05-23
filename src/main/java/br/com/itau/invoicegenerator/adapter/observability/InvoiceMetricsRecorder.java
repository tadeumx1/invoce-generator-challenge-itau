package br.com.itau.invoicegenerator.adapter.observability;

import br.com.itau.invoicegenerator.domain.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.domain.model.PersonType;
import br.com.itau.invoicegenerator.domain.model.Region;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * Strongly-typed entry point for the business counters that feed SLI dashboards: {@code
 * invoice.generated} and {@code invoice.rejected}. Tag values are restricted to bounded enums and
 * the {@link RejectionCode} allow-list so the cardinality budget defined in {@code
 * .specs/features/observability/spec.md} stays enforceable.
 *
 * <p>Forbidden tags ({@code orderId}, {@code invoiceId}, {@code correlationId}, {@code traceId},
 * {@code spanId}) are not accepted by this API — they live on logs and trace attributes only.
 */
public class InvoiceMetricsRecorder {

  static final String INVOICE_GENERATED_METRIC = "invoice.generated";
  static final String INVOICE_REJECTED_METRIC = "invoice.rejected";

  static final String TAG_TAX_REGIME = "tax_regime";
  static final String TAG_REGION = "region";
  static final String TAG_PERSON_TYPE = "person_type";
  static final String TAG_LARGE_ORDER = "large_order";
  static final String TAG_REASON = "reason";

  static final int LARGE_ORDER_THRESHOLD = 5;

  private final MeterRegistry registry;

  public InvoiceMetricsRecorder(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordGenerated(
      PersonType personType, CompanyTaxRegime taxRegime, Region region, int itemCount) {
    Tags tags =
        Tags.of(
            Tag.of(TAG_PERSON_TYPE, personType.name()),
            Tag.of(TAG_TAX_REGIME, taxRegime == null ? "NONE" : taxRegime.name()),
            Tag.of(TAG_REGION, region.name()),
            Tag.of(TAG_LARGE_ORDER, Boolean.toString(itemCount > LARGE_ORDER_THRESHOLD)));
    Counter.builder(INVOICE_GENERATED_METRIC).tags(tags).register(registry).increment();
  }

  public void recordRejected(String code) {
    RejectionCode reason = RejectionCode.fromCode(code);
    Counter.builder(INVOICE_REJECTED_METRIC)
        .tags(Tags.of(Tag.of(TAG_REASON, reason.name())))
        .register(registry)
        .increment();
  }
}
