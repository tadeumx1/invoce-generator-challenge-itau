package br.com.itau.invoicegenerator.application;

import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.InvoiceItem;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.domain.port.FreightCalculator;
import br.com.itau.invoicegenerator.domain.port.InvoiceSideEffectDispatcher;
import br.com.itau.invoicegenerator.domain.port.TaxRateCalculator;
import br.com.itau.invoicegenerator.domain.service.TaxRateTable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenerateInvoiceInteractor implements GenerateInvoiceUseCase {

  private static final Logger log = LoggerFactory.getLogger(GenerateInvoiceInteractor.class);

  private final TaxRateTable taxRateTable;
  private final TaxRateCalculator taxRateCalculator;
  private final FreightCalculator freightCalculator;
  private final InvoiceSideEffectDispatcher sideEffectDispatcher;

  public GenerateInvoiceInteractor(
      TaxRateTable taxRateTable,
      TaxRateCalculator taxRateCalculator,
      FreightCalculator freightCalculator,
      InvoiceSideEffectDispatcher sideEffectDispatcher) {
    this.taxRateTable = taxRateTable;
    this.taxRateCalculator = taxRateCalculator;
    this.freightCalculator = freightCalculator;
    this.sideEffectDispatcher = sideEffectDispatcher;
  }

  @Override
  public Invoice generateInvoice(Order order) {
    log.info("invoice generation begin orderId={}", order.getOrderId());
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

    // Side effects are dispatched asynchronously through Kafka (see adapter/messaging).
    // HTTP success means: invoice generated + Kafka publication accepted. It does NOT mean
    // stock, fiscal registration, delivery, and finance have completed.
    sideEffectDispatcher.dispatch(invoice);

    log.info(
        "invoice generation complete orderId={} invoiceId={}",
        order.getOrderId(),
        invoice.getInvoiceId());
    return invoice;
  }
}
