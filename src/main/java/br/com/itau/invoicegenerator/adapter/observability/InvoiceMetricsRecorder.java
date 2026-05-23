package br.com.itau.invoicegenerator.adapter.observability;

import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.domain.model.PersonType;
import br.com.itau.invoicegenerator.domain.model.Region;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Set;

/**
 * Strongly-typed entry point for the business counters and Kafka-side timers that feed SLI
 * dashboards: {@code invoice.generated}, {@code invoice.rejected}, {@code invoice.dispatch}, {@code
 * invoice.dispatch.duration}, and {@code invoice.sideeffect.duration}. Tag values are restricted to
 * bounded enums, the {@link RejectionCode} allow-list, and the {@link InvoiceTopics} constants so
 * the cardinality budget defined in {@code .specs/features/observability/spec.md} stays
 * enforceable.
 *
 * <p>Forbidden tags ({@code orderId}, {@code invoiceId}, {@code correlationId}, {@code traceId},
 * {@code spanId}) are not accepted by this API — they live on logs and trace attributes only.
 */
public class InvoiceMetricsRecorder {

  static final String INVOICE_GENERATED_METRIC = "invoice.generated";
  static final String INVOICE_REJECTED_METRIC = "invoice.rejected";
  static final String INVOICE_DISPATCH_METRIC = "invoice.dispatch";
  static final String INVOICE_DISPATCH_DURATION_METRIC = "invoice.dispatch.duration";
  static final String INVOICE_SIDEEFFECT_DURATION_METRIC = "invoice.sideeffect.duration";

  static final String TAG_TAX_REGIME = "tax_regime";
  static final String TAG_REGION = "region";
  static final String TAG_PERSON_TYPE = "person_type";
  static final String TAG_LARGE_ORDER = "large_order";
  static final String TAG_REASON = "reason";
  static final String TAG_TOPIC = "topic";
  static final String TAG_OUTCOME = "outcome";

  static final String OUTCOME_SUCCESS = "success";
  static final String OUTCOME_FAILURE = "failure";

  static final int LARGE_ORDER_THRESHOLD = 5;

  private static final Set<String> ALLOWED_TOPICS =
      Set.of(
          InvoiceTopics.STOCK_DEDUCTION,
          InvoiceTopics.INVOICE_REGISTRATION,
          InvoiceTopics.DELIVERY_SCHEDULING,
          InvoiceTopics.ACCOUNTS_RECEIVABLE);

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

  /** Per-producer-send dispatch counter + duration; backs SLI-3 (Kafka dispatch success). */
  public void recordDispatch(String topic, boolean success, Duration latency) {
    String safeTopic = requireKnownTopic(topic);
    String outcome = success ? OUTCOME_SUCCESS : OUTCOME_FAILURE;
    Counter.builder(INVOICE_DISPATCH_METRIC)
        .tags(Tags.of(Tag.of(TAG_TOPIC, safeTopic), Tag.of(TAG_OUTCOME, outcome)))
        .register(registry)
        .increment();
    Timer.builder(INVOICE_DISPATCH_DURATION_METRIC)
        .tags(Tags.of(Tag.of(TAG_TOPIC, safeTopic)))
        .register(registry)
        .record(latency);
  }

  /**
   * Producer-publish → consumer-ack latency; backs SLI-4. Called from the consumer interceptor with
   * the producer-side {@code publishedAtEpochMillis} header.
   */
  public void recordSideEffect(String topic, long producerPublishEpochMillis) {
    String safeTopic = requireKnownTopic(topic);
    long elapsedMs = Math.max(0L, System.currentTimeMillis() - producerPublishEpochMillis);
    Timer.builder(INVOICE_SIDEEFFECT_DURATION_METRIC)
        .tags(Tags.of(Tag.of(TAG_TOPIC, safeTopic)))
        .register(registry)
        .record(Duration.ofMillis(elapsedMs));
  }

  private static String requireKnownTopic(String topic) {
    if (topic == null || !ALLOWED_TOPICS.contains(topic)) {
      throw new IllegalArgumentException(
          "unknown topic '" + topic + "' — add it to InvoiceTopics before emitting metrics");
    }
    return topic;
  }
}
