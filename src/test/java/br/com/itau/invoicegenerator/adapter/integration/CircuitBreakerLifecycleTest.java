package br.com.itau.invoicegenerator.adapter.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * F-RESILIENCE T1 — proves the Resilience4j circuit breaker contract that every outbound port
 * relies on: enough failures within the sliding window flip the breaker to OPEN, OPEN rejects calls
 * fast with {@link CallNotPermittedException}, and a half-open probe success closes it again.
 *
 * <p>Uses the registry-free programmatic API with tightened thresholds so the lifecycle runs in
 * milliseconds — that keeps it inside the fast suite while still exercising the real Resilience4j
 * state machine that the adapters use in production.
 */
class CircuitBreakerLifecycleTest {

  @Test
  void openAfterFailureRateThresholdThenHalfOpenThenClosed() {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(4)
            .minimumNumberOfCalls(4)
            .waitDurationInOpenState(Duration.ofMillis(50))
            .permittedNumberOfCallsInHalfOpenState(2)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build();
    CircuitBreaker cb = CircuitBreaker.of("test", config);

    Runnable failing =
        () -> {
          throw new IntegrationAdapterException("test", "simulated outage", new RuntimeException());
        };
    Runnable succeeding = () -> {};

    // 4 failing calls fill the window past the failure-rate threshold.
    for (int i = 0; i < 4; i++) {
      assertThrows(IntegrationAdapterException.class, () -> cb.executeRunnable(failing));
    }

    assertEquals(CircuitBreaker.State.OPEN, cb.getState(), "CB should be OPEN after threshold");
    assertThrows(
        CallNotPermittedException.class,
        () -> cb.executeRunnable(succeeding),
        "OPEN state must reject calls fast");

    // Drive to half-open via the wait-duration timer.
    sleep(150);
    cb.executeRunnable(succeeding);
    assertEquals(
        CircuitBreaker.State.HALF_OPEN,
        cb.getState(),
        "first probe should put CB in HALF_OPEN until permitted-number-of-calls reached");

    // Second probe closes the breaker.
    cb.executeRunnable(succeeding);
    assertEquals(
        CircuitBreaker.State.CLOSED,
        cb.getState(),
        "two consecutive successes in HALF_OPEN should close the breaker");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }
}
