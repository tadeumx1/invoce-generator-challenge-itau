package br.com.itau.invoicegenerator.adapter.observability;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.domain.model.PersonType;
import br.com.itau.invoicegenerator.domain.model.Region;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * F-OBSERVABILITY cardinality guard — exercises {@link InvoiceMetricsRecorder} once per public call
 * site, then iterates every registered meter and asserts none carries a forbidden tag ({@code
 * orderId}, {@code invoiceId}, {@code correlationId}, {@code traceId}, {@code spanId}).
 *
 * <p>Catching a forbidden tag at test time is the cheapest gate against Prometheus / CloudWatch
 * cardinality blow-ups. If a future change starts emitting one of the forbidden tags, this test
 * fails and the change is forced through a code review.
 */
class CardinalityGuardTest {

  private static final Set<String> FORBIDDEN_TAGS =
      Set.of("orderId", "invoiceId", "correlationId", "traceId", "spanId");

  @Test
  void noRegisteredMeterCarriesAForbiddenTag() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InvoiceMetricsRecorder recorder = new InvoiceMetricsRecorder(registry);

    recorder.recordGenerated(PersonType.FISICA, null, Region.SUDESTE, 2);
    recorder.recordGenerated(PersonType.JURIDICA, CompanyTaxRegime.LUCRO_PRESUMIDO, Region.SUL, 8);
    for (RejectionCode reason : RejectionCode.values()) {
      recorder.recordRejected(reason.name());
    }
    recorder.recordDispatch(InvoiceTopics.STOCK_DEDUCTION, true, Duration.ofMillis(7));
    recorder.recordDispatch(InvoiceTopics.INVOICE_REGISTRATION, false, Duration.ofMillis(7));
    recorder.recordDispatch(InvoiceTopics.DELIVERY_SCHEDULING, true, Duration.ofMillis(7));
    recorder.recordDispatch(InvoiceTopics.ACCOUNTS_RECEIVABLE, true, Duration.ofMillis(7));
    recorder.recordSideEffect(InvoiceTopics.STOCK_DEDUCTION, System.currentTimeMillis() - 5);
    recorder.recordSideEffect(InvoiceTopics.INVOICE_REGISTRATION, System.currentTimeMillis() - 5);

    for (Meter meter : registry.getMeters()) {
      for (Tag tag : meter.getId().getTags()) {
        assertTrue(
            !FORBIDDEN_TAGS.contains(tag.getKey()),
            "meter "
                + meter.getId().getName()
                + " carries forbidden tag "
                + tag.getKey()
                + " — move this identifier to logs/trace attributes only");
      }
    }
  }
}
