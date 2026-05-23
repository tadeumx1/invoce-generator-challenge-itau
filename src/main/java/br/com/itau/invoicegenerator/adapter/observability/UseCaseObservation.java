package br.com.itau.invoicegenerator.adapter.observability;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.Order;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Thin adapter-layer wrapper that creates the {@code invoice.generate} child span around the
 * use-case call. Keeping the observation in the adapter layer preserves AD-009 — the {@code
 * application/} package never imports Micrometer or any Spring observability API.
 *
 * <p>When no {@link ObservationRegistry} is wired (e.g., bare unit tests), the wrapper degrades to
 * a straight delegate call so domain tests stay independent of the observability stack.
 */
public class UseCaseObservation {

  public static final String OBSERVATION_NAME = "invoice.generate";

  private final GenerateInvoiceUseCase delegate;
  private final ObservationRegistry observationRegistry;

  public UseCaseObservation(
      GenerateInvoiceUseCase delegate, ObservationRegistry observationRegistry) {
    this.delegate = delegate;
    this.observationRegistry = observationRegistry;
  }

  public Invoice generate(Order order) {
    if (observationRegistry == null || observationRegistry.isNoop()) {
      return delegate.generateInvoice(order);
    }
    return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
        .observe(() -> delegate.generateInvoice(order));
  }
}
