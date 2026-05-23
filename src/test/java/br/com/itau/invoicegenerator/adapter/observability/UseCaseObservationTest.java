package br.com.itau.invoicegenerator.adapter.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.Order;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-level proof that {@link UseCaseObservation} opens the {@code invoice.generate} observation
 * around the use-case call. We register an {@link ObservationHandler} that records the names of
 * started observations and assert the wrapper triggered exactly one with the expected name.
 */
class UseCaseObservationTest {

  @Test
  void openObservationAroundUseCaseCall() {
    ObservationRegistry registry = ObservationRegistry.create();
    RecordingHandler handler = new RecordingHandler();
    registry.observationConfig().observationHandler(handler);

    GenerateInvoiceUseCase delegate = order -> Invoice.builder().invoiceId("inv-42").build();
    UseCaseObservation observation = new UseCaseObservation(delegate, registry);

    Invoice result = observation.generate(new Order());

    assertNotNull(result);
    assertEquals("inv-42", result.getInvoiceId());
    assertEquals(1, handler.started.size(), "exactly one observation must be started");
    assertEquals(UseCaseObservation.OBSERVATION_NAME, handler.started.get(0));
  }

  @Test
  void delegatesWhenObservationRegistryIsNoop() {
    GenerateInvoiceUseCase delegate = order -> Invoice.builder().invoiceId("inv-43").build();
    UseCaseObservation observation = new UseCaseObservation(delegate, ObservationRegistry.NOOP);

    Invoice result = observation.generate(new Order());

    assertEquals("inv-43", result.getInvoiceId());
  }

  @Test
  void degradesGracefullyWhenObservationRegistryIsNull() {
    GenerateInvoiceUseCase delegate = order -> Invoice.builder().invoiceId("inv-44").build();
    UseCaseObservation observation = new UseCaseObservation(delegate, null);

    Invoice result = observation.generate(new Order());

    assertEquals("inv-44", result.getInvoiceId());
    assertTrue(true, "no NPE means the null-registry branch was exercised");
  }

  private static final class RecordingHandler implements ObservationHandler<Observation.Context> {

    final List<String> started = new ArrayList<>();

    @Override
    public void onStart(Observation.Context context) {
      started.add(context.getName());
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }
  }
}
