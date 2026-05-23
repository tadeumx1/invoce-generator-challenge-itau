package br.com.itau.invoicegenerator.testsupport;

import br.com.itau.invoicegenerator.adapter.integration.delivery.DeliveryIntegrationAdapter;
import br.com.itau.invoicegenerator.adapter.integration.delivery.DeliverySchedulingClient;
import br.com.itau.invoicegenerator.adapter.integration.finance.AccountsReceivableAdapter;
import br.com.itau.invoicegenerator.adapter.integration.registration.InvoiceRegistrationAdapter;
import br.com.itau.invoicegenerator.adapter.integration.stock.StockIntegrationAdapter;
import br.com.itau.invoicegenerator.application.GenerateInvoiceInteractor;
import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.port.FreightCalculator;
import br.com.itau.invoicegenerator.domain.port.InvoiceSideEffectDispatcher;
import br.com.itau.invoicegenerator.domain.port.TaxRateCalculator;
import br.com.itau.invoicegenerator.domain.service.LegacyFreightCalculator;
import br.com.itau.invoicegenerator.domain.service.TaxRateTable;

public final class TestUseCases {

  private TestUseCases() {}

  /** Use case wired with a no-op side-effect dispatcher; for unit tests of orchestration only. */
  public static GenerateInvoiceUseCase generateInvoiceUseCase(TaxRateCalculator taxRateCalculator) {
    return generateInvoiceUseCase(taxRateCalculator, noOpDispatcher());
  }

  /**
   * Use case wired with a dispatcher that invokes the four real integration adapters
   * <em>synchronously</em>. Mirrors what Kafka consumers will do in T2, so existing
   * characterization tests (e.g., {@code SlowDeliveryCharacterizationTest}) can still observe the
   * full pre-Kafka latency behavior. T2 will flip those tests to assert HTTP-bounded latency on the
   * request path and slow latency on the consumer path.
   */
  public static GenerateInvoiceUseCase generateInvoiceUseCaseWithRealAdapters(
      TaxRateCalculator taxRateCalculator) {
    StockIntegrationAdapter stock = new StockIntegrationAdapter();
    InvoiceRegistrationAdapter registration = new InvoiceRegistrationAdapter();
    DeliveryIntegrationAdapter delivery =
        new DeliveryIntegrationAdapter(new DeliverySchedulingClient());
    AccountsReceivableAdapter finance = new AccountsReceivableAdapter();
    InvoiceSideEffectDispatcher inlineDispatcher =
        invoice -> {
          stock.sendInvoiceForStockDeduction(invoice);
          registration.registerInvoice(invoice);
          delivery.scheduleDelivery(invoice);
          finance.sendInvoiceToAccountsReceivable(invoice);
        };
    return generateInvoiceUseCase(taxRateCalculator, inlineDispatcher);
  }

  private static GenerateInvoiceUseCase generateInvoiceUseCase(
      TaxRateCalculator taxRateCalculator, InvoiceSideEffectDispatcher sideEffectDispatcher) {
    FreightCalculator freightCalculator = new LegacyFreightCalculator();
    return new GenerateInvoiceInteractor(
        new TaxRateTable(), taxRateCalculator, freightCalculator, sideEffectDispatcher);
  }

  private static InvoiceSideEffectDispatcher noOpDispatcher() {
    return ignored -> {};
  }
}
