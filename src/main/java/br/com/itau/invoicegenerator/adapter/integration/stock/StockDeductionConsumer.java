package br.com.itau.invoicegenerator.adapter.integration.stock;

import br.com.itau.invoicegenerator.adapter.messaging.IntegrationEvent;
import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.port.StockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** Consumes invoice stock-deduction events and invokes the existing {@link StockPort} adapter. */
public class StockDeductionConsumer {

  private static final Logger log = LoggerFactory.getLogger(StockDeductionConsumer.class);

  private final StockPort stockPort;

  public StockDeductionConsumer(StockPort stockPort) {
    this.stockPort = stockPort;
  }

  @KafkaListener(
      topics = InvoiceTopics.STOCK_DEDUCTION,
      groupId = "invoice-generator-stock-deduction")
  public void onEvent(IntegrationEvent event, Acknowledgment ack) {
    log.debug(
        "consuming stock deduction event eventId={} invoiceId={}",
        event.eventId(),
        event.invoiceId());
    stockPort.sendInvoiceForStockDeduction(event.invoice());
    ack.acknowledge();
  }
}
