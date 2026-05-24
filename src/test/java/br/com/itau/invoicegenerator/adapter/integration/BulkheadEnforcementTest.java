package br.com.itau.invoicegenerator.adapter.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * F-BULKHEAD T2 — proves the Resilience4j semaphore bulkhead contract that every outbound port
 * relies on: once {@code max-concurrent-calls} permits are held, the next call is rejected fast
 * with {@link BulkheadFullException}; releasing a permit lets the next call proceed.
 *
 * <p>Mirrors the {@code CircuitBreakerLifecycleTest} pattern: programmatic Resilience4j API with
 * tightened thresholds so the lifecycle runs in milliseconds. The production adapters declare
 * {@code @Bulkhead(name = ...)} on the same {@link Bulkhead} type this test exercises.
 */
class BulkheadEnforcementTest {

  @Test
  void rejectsCallBeyondMaxConcurrentCallsAndRecoversAfterRelease() throws InterruptedException {
    BulkheadConfig config =
        BulkheadConfig.custom()
            .maxConcurrentCalls(3)
            .maxWaitDuration(Duration.ZERO) // fail-fast
            .build();
    Bulkhead bulkhead = Bulkhead.of("test-delivery", config);

    CountDownLatch holdPermits = new CountDownLatch(1);
    CountDownLatch threeInFlight = new CountDownLatch(3);
    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      // Saturate the bulkhead: 3 long-running calls each take and hold a permit.
      for (int i = 0; i < 3; i++) {
        executor.submit(
            () ->
                bulkhead.executeRunnable(
                    () -> {
                      threeInFlight.countDown();
                      try {
                        holdPermits.await(2, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
      }
      assertTrue(
          threeInFlight.await(2, TimeUnit.SECONDS),
          "three concurrent calls should be in flight before the 4th arrives");
      assertEquals(
          0, bulkhead.getMetrics().getAvailableConcurrentCalls(), "all permits should be in use");

      // The 4th call must be rejected immediately, NOT queued.
      assertThrows(
          BulkheadFullException.class,
          () -> bulkhead.executeRunnable(() -> {}),
          "max-wait-duration=0 must fail fast when permits are exhausted");

      // Release the held permits — next call should now proceed.
      holdPermits.countDown();
      executor.shutdown();
      assertTrue(
          executor.awaitTermination(2, TimeUnit.SECONDS), "held calls should complete promptly");

      assertEquals(
          3,
          bulkhead.getMetrics().getAvailableConcurrentCalls(),
          "permits must be returned after the held calls finish");

      // And the next call goes through cleanly.
      bulkhead.executeRunnable(() -> {});
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void maxAllowedConcurrentCallsMatchesConfig() {
    BulkheadConfig config =
        BulkheadConfig.custom().maxConcurrentCalls(5).maxWaitDuration(Duration.ZERO).build();
    Bulkhead bulkhead = Bulkhead.of("test-stock", config);

    assertEquals(
        5,
        bulkhead.getMetrics().getMaxAllowedConcurrentCalls(),
        "max-allowed-concurrent-calls is the meter Prometheus scrapes — must match config");
    assertEquals(
        5,
        bulkhead.getMetrics().getAvailableConcurrentCalls(),
        "available permits start at the configured maximum");
  }
}
