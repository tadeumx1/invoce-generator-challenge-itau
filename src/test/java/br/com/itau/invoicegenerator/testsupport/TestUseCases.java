package br.com.itau.invoicegenerator.testsupport;

import br.com.itau.invoicegenerator.adapter.integration.delivery.DeliveryIntegrationAdapter;
import br.com.itau.invoicegenerator.adapter.integration.delivery.DeliverySchedulingClient;
import br.com.itau.invoicegenerator.adapter.integration.finance.AccountsReceivableAdapter;
import br.com.itau.invoicegenerator.adapter.integration.registration.InvoiceRegistrationAdapter;
import br.com.itau.invoicegenerator.adapter.integration.stock.StockIntegrationAdapter;
import br.com.itau.invoicegenerator.application.GenerateInvoiceInteractor;
import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.port.AccountsReceivablePort;
import br.com.itau.invoicegenerator.domain.port.DeliveryPort;
import br.com.itau.invoicegenerator.domain.port.FreightCalculator;
import br.com.itau.invoicegenerator.domain.port.InvoiceRegistrationPort;
import br.com.itau.invoicegenerator.domain.port.StockPort;
import br.com.itau.invoicegenerator.domain.port.TaxRateCalculator;
import br.com.itau.invoicegenerator.domain.service.LegacyFreightCalculator;
import br.com.itau.invoicegenerator.domain.service.TaxRateTable;

public final class TestUseCases {

  private TestUseCases() {}

  public static GenerateInvoiceUseCase generateInvoiceUseCase(TaxRateCalculator taxRateCalculator) {
    return generateInvoiceUseCase(
        taxRateCalculator, noOpStock(), noOpRegistration(), noOpDelivery(), noOpFinance());
  }

  public static GenerateInvoiceUseCase generateInvoiceUseCaseWithRealAdapters(
      TaxRateCalculator taxRateCalculator) {
    return generateInvoiceUseCase(
        taxRateCalculator,
        new StockIntegrationAdapter(),
        new InvoiceRegistrationAdapter(),
        new DeliveryIntegrationAdapter(new DeliverySchedulingClient()),
        new AccountsReceivableAdapter());
  }

  private static GenerateInvoiceUseCase generateInvoiceUseCase(
      TaxRateCalculator taxRateCalculator,
      StockPort stockPort,
      InvoiceRegistrationPort invoiceRegistrationPort,
      DeliveryPort deliveryPort,
      AccountsReceivablePort accountsReceivablePort) {
    FreightCalculator freightCalculator = new LegacyFreightCalculator();
    return new GenerateInvoiceInteractor(
        new TaxRateTable(),
        taxRateCalculator,
        freightCalculator,
        stockPort,
        invoiceRegistrationPort,
        deliveryPort,
        accountsReceivablePort);
  }

  private static StockPort noOpStock() {
    return ignored -> {};
  }

  private static InvoiceRegistrationPort noOpRegistration() {
    return ignored -> {};
  }

  private static DeliveryPort noOpDelivery() {
    return ignored -> {};
  }

  private static AccountsReceivablePort noOpFinance() {
    return ignored -> {};
  }
}
