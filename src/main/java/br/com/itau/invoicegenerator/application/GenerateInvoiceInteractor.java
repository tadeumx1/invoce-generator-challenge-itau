package br.com.itau.invoicegenerator.application;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.InvoiceItem;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.domain.port.AccountsReceivablePort;
import br.com.itau.invoicegenerator.domain.port.DeliveryPort;
import br.com.itau.invoicegenerator.domain.port.FreightCalculator;
import br.com.itau.invoicegenerator.domain.port.InvoiceRegistrationPort;
import br.com.itau.invoicegenerator.domain.port.StockPort;
import br.com.itau.invoicegenerator.domain.port.TaxRateCalculator;
import br.com.itau.invoicegenerator.domain.service.TaxRateTable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GenerateInvoiceInteractor implements GenerateInvoiceUseCase {

  private final TaxRateTable taxRateTable;
  private final TaxRateCalculator taxRateCalculator;
  private final FreightCalculator freightCalculator;
  private final StockPort stockPort;
  private final InvoiceRegistrationPort invoiceRegistrationPort;
  private final DeliveryPort deliveryPort;
  private final AccountsReceivablePort accountsReceivablePort;

  public GenerateInvoiceInteractor(
      TaxRateTable taxRateTable,
      TaxRateCalculator taxRateCalculator,
      FreightCalculator freightCalculator,
      StockPort stockPort,
      InvoiceRegistrationPort invoiceRegistrationPort,
      DeliveryPort deliveryPort,
      AccountsReceivablePort accountsReceivablePort) {
    this.taxRateTable = taxRateTable;
    this.taxRateCalculator = taxRateCalculator;
    this.freightCalculator = freightCalculator;
    this.stockPort = stockPort;
    this.invoiceRegistrationPort = invoiceRegistrationPort;
    this.deliveryPort = deliveryPort;
    this.accountsReceivablePort = accountsReceivablePort;
  }

  @Override
  public Invoice generateInvoice(Order order) {
    List<InvoiceItem> invoiceItems = new ArrayList<>();
    var taxRate = taxRateTable.findRate(order);

    if (taxRate.isPresent()) {
      invoiceItems = taxRateCalculator.calculateTax(order.getItems(), taxRate.get());
    }

    Invoice invoice =
        Invoice.builder()
            .invoiceId(UUID.randomUUID().toString())
            .date(LocalDateTime.now())
            .totalItemsValue(order.getTotalItemsValue())
            .freightValue(freightCalculator.calculateFreight(order))
            .items(invoiceItems)
            .recipient(order.getRecipient())
            .build();

    stockPort.sendInvoiceForStockDeduction(invoice);
    invoiceRegistrationPort.registerInvoice(invoice);
    deliveryPort.scheduleDelivery(invoice);
    accountsReceivablePort.sendInvoiceToAccountsReceivable(invoice);

    return invoice;
  }
}
