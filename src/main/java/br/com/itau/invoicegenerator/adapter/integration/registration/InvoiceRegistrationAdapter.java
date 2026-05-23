package br.com.itau.invoicegenerator.adapter.integration.registration;

import br.com.itau.invoicegenerator.adapter.integration.IntegrationAdapterException;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.InvoiceRegistrationPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

@Component
public class InvoiceRegistrationAdapter implements InvoiceRegistrationPort {

  public static final String CIRCUIT_BREAKER_NAME = "invoiceRegistrationPort";

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER_NAME)
  public void registerInvoice(Invoice invoice) {
    try {
      // Simulates registering the invoice with the registry.
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IntegrationAdapterException(
          CIRCUIT_BREAKER_NAME, "interrupted during invoice registration call", e);
    }
  }
}
