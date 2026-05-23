package br.com.itau.invoicegenerator.adapter.integration;

/**
 * Typed runtime exception thrown by outbound integration adapters when a simulated external call
 * fails or is interrupted. Distinguishes integration-level failures from unrelated runtime errors
 * so that Resilience4j circuit breakers, retry/DLT routing, and observability all see a stable
 * exception class.
 *
 * <p>F-RESILIENCE T1 introduces this class together with the C-8 fix ({@code
 * Thread.currentThread().interrupt()} on {@link InterruptedException}).
 */
public class IntegrationAdapterException extends RuntimeException {

  private final String integration;

  public IntegrationAdapterException(String integration, String message, Throwable cause) {
    super("integration=" + integration + " message=" + message, cause);
    this.integration = integration;
  }

  public String getIntegration() {
    return integration;
  }
}
