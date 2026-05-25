package br.com.itau.invoicegenerator.observability;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.itau.invoicegenerator.adapter.observability.ResilienceEventLogger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Drives the {@link ResilienceEventLogger}'s @PostConstruct wiring by hand to prove the WARN line
 * fires when a circuit breaker opens and that a rejected bulkhead permit also logs at WARN.
 */
class ResilienceEventLoggerTest {

  private static final String LOGGER_NAME =
      "br.com.itau.invoicegenerator.adapter.observability.ResilienceEventLogger";

  private ListAppender<ILoggingEvent> appender;
  private ch.qos.logback.classic.Logger logger;
  private Level previousLevel;

  @BeforeEach
  void attachAppender() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    logger = context.getLogger(LOGGER_NAME);
    previousLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    logger.setLevel(previousLevel);
  }

  @Test
  void circuitBreakerOpenTransitionEmitsWarn() throws Exception {
    CircuitBreakerRegistry cbRegistry =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
    BulkheadRegistry bulkheadRegistry = BulkheadRegistry.ofDefaults();
    ResilienceEventLogger eventLogger = new ResilienceEventLogger(cbRegistry, bulkheadRegistry);
    invokePostConstruct(eventLogger);

    CircuitBreaker cb = cbRegistry.circuitBreaker("test-cb");
    cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("boom"));

    assertThat(appender.list)
        .anyMatch(
            event ->
                event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("circuit breaker state transition")
                    && event.getFormattedMessage().contains("name=test-cb")
                    && event.getFormattedMessage().contains("to=OPEN"));
  }

  @Test
  void bulkheadRejectedPermitEmitsWarn() throws Exception {
    CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.ofDefaults();
    BulkheadRegistry bulkheadRegistry =
        BulkheadRegistry.of(
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    ResilienceEventLogger eventLogger = new ResilienceEventLogger(cbRegistry, bulkheadRegistry);
    invokePostConstruct(eventLogger);

    Bulkhead bulkhead = bulkheadRegistry.bulkhead("test-bh");
    boolean firstPermit = bulkhead.tryAcquirePermission();
    boolean secondPermit = bulkhead.tryAcquirePermission();
    assertThat(firstPermit).isTrue();
    assertThat(secondPermit).isFalse();

    assertThat(appender.list)
        .anyMatch(
            event ->
                event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("bulkhead rejected")
                    && event.getFormattedMessage().contains("name=test-bh"));
  }

  private static void invokePostConstruct(ResilienceEventLogger logger) throws Exception {
    var method = ResilienceEventLogger.class.getDeclaredMethod("attach");
    method.setAccessible(true);
    method.invoke(logger);
  }
}
