package br.com.itau.invoicegenerator.adapter.integration.stock;

import br.com.itau.invoicegenerator.adapter.integration.IntegrationAdapterException;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.StockPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

@Component
public class StockIntegrationAdapter implements StockPort {

  public static final String CIRCUIT_BREAKER_NAME = "stockPort";

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER_NAME)
  public void sendInvoiceForStockDeduction(Invoice invoice) {
    try {
      // Simulates sending the invoice for stock deduction.
      Thread.sleep(380);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IntegrationAdapterException(
          CIRCUIT_BREAKER_NAME, "interrupted during stock deduction call", e);
    }
  }
}
