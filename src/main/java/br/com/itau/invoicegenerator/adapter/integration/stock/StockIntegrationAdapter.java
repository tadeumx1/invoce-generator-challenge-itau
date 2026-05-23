package br.com.itau.invoicegenerator.adapter.integration.stock;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.port.StockPort;
import org.springframework.stereotype.Component;

@Component
public class StockIntegrationAdapter implements StockPort {

  @Override
  public void sendInvoiceForStockDeduction(Invoice invoice) {
    try {
      // Simulates sending the invoice for stock deduction.
      Thread.sleep(380);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
