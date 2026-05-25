package br.com.itau.invoicegenerator.adapter.observability;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * F-DEBUG-LOGS T4 — subscribes to Resilience4j event publishers and logs state transitions and
 * bulkhead rejections at WARN / INFO. The Resilience4j Micrometer bindings already publish
 * `resilience4j.circuitbreaker.state` and `resilience4j.bulkhead.available.concurrent.calls` to
 * Prometheus; this logger is the *log* signal counterpart so an on-call engineer reading CloudWatch
 * Logs alone can see a circuit-breaker open or a bulkhead reject without subscribing to the metrics
 * backend.
 */
@Component
public class ResilienceEventLogger {

  private static final Logger log = LoggerFactory.getLogger(ResilienceEventLogger.class);

  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final BulkheadRegistry bulkheadRegistry;

  public ResilienceEventLogger(
      CircuitBreakerRegistry circuitBreakerRegistry, BulkheadRegistry bulkheadRegistry) {
    this.circuitBreakerRegistry = circuitBreakerRegistry;
    this.bulkheadRegistry = bulkheadRegistry;
  }

  @PostConstruct
  void attach() {
    circuitBreakerRegistry.getAllCircuitBreakers().forEach(this::registerCircuitBreakerListener);
    circuitBreakerRegistry
        .getEventPublisher()
        .onEntryAdded(entry -> registerCircuitBreakerListener(entry.getAddedEntry()));

    bulkheadRegistry.getAllBulkheads().forEach(this::registerBulkheadListener);
    bulkheadRegistry
        .getEventPublisher()
        .onEntryAdded(entry -> registerBulkheadListener(entry.getAddedEntry()));
  }

  private void registerCircuitBreakerListener(CircuitBreaker cb) {
    cb.getEventPublisher()
        .onStateTransition(
            event -> {
              CircuitBreaker.State to = event.getStateTransition().getToState();
              String message = "circuit breaker state transition name={} from={} to={}";
              if (to == CircuitBreaker.State.OPEN) {
                log.warn(message, cb.getName(), event.getStateTransition().getFromState(), to);
              } else {
                log.info(message, cb.getName(), event.getStateTransition().getFromState(), to);
              }
            });
  }

  private void registerBulkheadListener(io.github.resilience4j.bulkhead.Bulkhead bulkhead) {
    bulkhead
        .getEventPublisher()
        .onCallRejected(
            event ->
                log.warn(
                    "bulkhead rejected name={} reason=permit-not-available", bulkhead.getName()));
  }
}
