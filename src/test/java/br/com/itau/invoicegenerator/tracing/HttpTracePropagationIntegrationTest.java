package br.com.itau.invoicegenerator.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.itau.invoicegenerator.adapter.observability.CorrelationIdFilter;
import br.com.itau.invoicegenerator.adapter.observability.UseCaseObservation;
import br.com.itau.invoicegenerator.testsupport.NoOpKafkaTestConfig;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StreamUtils;

/**
 * F-OBSERVABILITY T4 — proves end-to-end tracing wiring:
 *
 * <ul>
 *   <li>The {@code invoice.generate} observation defined by {@link UseCaseObservation} is opened on
 *       every request through the application {@link ObservationRegistry}.
 *   <li>{@code correlationId} and {@code traceId} are present in MDC when the observation starts —
 *       proves both the {@link CorrelationIdFilter} (T2) and the Micrometer Tracing OTel bridge
 *       (T4) are wired and populating MDC for the duration of the request.
 *   <li>The response echoes back the {@link CorrelationIdFilter#HEADER_NAME} header.
 * </ul>
 *
 * <p>Verifying trace IDs through MDC requires sampling the value inside the request thread. We
 * register a one-shot {@link ObservationHandler} that snapshots MDC at observation-start time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Import(NoOpKafkaTestConfig.class)
@TestPropertySource(
    properties = {
      "app.messaging.kafka.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
class HttpTracePropagationIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObservationRegistry observationRegistry;

  private MdcSnapshotHandler snapshotHandler;

  @BeforeEach
  void registerSnapshotHandler() {
    snapshotHandler = new MdcSnapshotHandler();
    observationRegistry.observationConfig().observationHandler(snapshotHandler);
  }

  @Test
  void requestEchoesCorrelationIdAndOpensInvoiceGenerateObservation() throws Exception {
    String body = loadFixture("payloads/teste-pf.json");

    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .header(CorrelationIdFilter.HEADER_NAME, "probe-trace")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                assertEquals(
                    "probe-trace",
                    result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME)));

    boolean openedInvoiceGenerate =
        snapshotHandler.startedObservations.contains(UseCaseObservation.OBSERVATION_NAME);
    assertEquals(
        true,
        openedInvoiceGenerate,
        "invoice.generate observation must be opened during the request");

    // During the request, the CorrelationIdFilter pushed correlationId to MDC. The snapshot the
    // handler took on observation-start should contain it.
    List<MdcSnapshot> snapshots = snapshotHandler.snapshotsFor(UseCaseObservation.OBSERVATION_NAME);
    assertFalse(snapshots.isEmpty(), "expected a snapshot for invoice.generate observation");
    MdcSnapshot snapshot = snapshots.get(0);
    assertEquals(
        "probe-trace",
        snapshot.correlationId,
        "correlationId from the X-Correlation-Id header must be in MDC during the use case call");
    assertNotNull(snapshot.traceId, "Micrometer Tracing must populate traceId in MDC");
  }

  private static String loadFixture(String classpathLocation) throws Exception {
    try (var in = new ClassPathResource(classpathLocation).getInputStream()) {
      return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
    }
  }

  private record MdcSnapshot(String correlationId, String traceId, String spanId) {}

  private static final class MdcSnapshotHandler implements ObservationHandler<Observation.Context> {

    final List<String> startedObservations = new CopyOnWriteArrayList<>();
    final List<NamedSnapshot> snapshots = new CopyOnWriteArrayList<>();

    @Override
    public void onStart(Observation.Context context) {
      startedObservations.add(context.getName());
      snapshots.add(
          new NamedSnapshot(
              context.getName(),
              new MdcSnapshot(MDC.get("correlationId"), MDC.get("traceId"), MDC.get("spanId"))));
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    List<MdcSnapshot> snapshotsFor(String name) {
      List<MdcSnapshot> matching = new ArrayList<>();
      for (NamedSnapshot snapshot : snapshots) {
        if (name.equals(snapshot.name())) {
          matching.add(snapshot.snapshot());
        }
      }
      return matching;
    }

    private record NamedSnapshot(String name, MdcSnapshot snapshot) {}
  }
}
