package br.com.itau.invoicegenerator.adapter.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.itau.invoicegenerator.domain.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.domain.model.PersonType;
import br.com.itau.invoicegenerator.domain.model.Region;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class InvoiceMetricsRecorderTest {

  @Test
  void recordGeneratedTagsAreCardinalitySafe() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InvoiceMetricsRecorder recorder = new InvoiceMetricsRecorder(registry);

    recorder.recordGenerated(PersonType.FISICA, null, Region.SUDESTE, 3);
    recorder.recordGenerated(PersonType.JURIDICA, CompanyTaxRegime.SIMPLES_NACIONAL, Region.SUL, 7);

    assertEquals(
        1.0,
        registry
            .find(InvoiceMetricsRecorder.INVOICE_GENERATED_METRIC)
            .tag("person_type", "FISICA")
            .tag("tax_regime", "NONE")
            .tag("region", "SUDESTE")
            .tag("large_order", "false")
            .counter()
            .count());
    assertEquals(
        1.0,
        registry
            .find(InvoiceMetricsRecorder.INVOICE_GENERATED_METRIC)
            .tag("person_type", "JURIDICA")
            .tag("tax_regime", "SIMPLES_NACIONAL")
            .tag("region", "SUL")
            .tag("large_order", "true")
            .counter()
            .count());
  }

  @Test
  void recordRejectedUsesKnownReasonOnly() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InvoiceMetricsRecorder recorder = new InvoiceMetricsRecorder(registry);

    recorder.recordRejected("UNSUPPORTED_TAX_REGIME");
    recorder.recordRejected("UNSUPPORTED_TAX_REGIME");
    recorder.recordRejected("INVALID_DELIVERY_REGION");

    assertEquals(
        2.0,
        registry
            .find(InvoiceMetricsRecorder.INVOICE_REJECTED_METRIC)
            .tag("reason", "UNSUPPORTED_TAX_REGIME")
            .counter()
            .count());
    assertEquals(
        1.0,
        registry
            .find(InvoiceMetricsRecorder.INVOICE_REJECTED_METRIC)
            .tag("reason", "INVALID_DELIVERY_REGION")
            .counter()
            .count());
  }

  @Test
  void unknownRejectionCodeIsRejected() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InvoiceMetricsRecorder recorder = new InvoiceMetricsRecorder(registry);

    assertThrows(IllegalArgumentException.class, () -> recorder.recordRejected("MADE_UP_CODE"));
    assertThrows(IllegalArgumentException.class, () -> recorder.recordRejected(null));
  }
}
