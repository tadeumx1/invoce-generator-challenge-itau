package br.com.itau.invoicegenerator.adapter.integration.stock;

import br.com.itau.invoicegenerator.adapter.messaging.IdempotencyStore;
import br.com.itau.invoicegenerator.adapter.messaging.IntegrationEvent;
import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.port.StockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;

/**
 * Consumes invoice stock-deduction events and invokes the existing {@link StockPort} adapter.
 *
 * <p>Idempotent: a previously processed {@code (topic, eventId)} short-circuits before the port
 * call. {@code @RetryableTopic} routes transient failures through delayed retry topics and
 * exhausted failures to the DLT.
 */
public class StockDeductionConsumer {

  private static final Logger log = LoggerFactory.getLogger(StockDeductionConsumer.class);

  private final StockPort stockPort;
  private final IdempotencyStore idempotencyStore;

  public StockDeductionConsumer(StockPort stockPort, IdempotencyStore idempotencyStore) {
    this.stockPort = stockPort;
    this.idempotencyStore = idempotencyStore;
  }

  @RetryableTopic(
      attempts = "${app.kafka.retry.attempts:4}",
      backoff =
          @Backoff(
              delayExpression = "${app.kafka.retry.delay-ms:60000}",
              multiplierExpression = "${app.kafka.retry.multiplier:5.0}"),
      autoCreateTopics = "true",
      dltStrategy = DltStrategy.FAIL_ON_ERROR)
  @KafkaListener(
      topics = InvoiceTopics.STOCK_DEDUCTION,
      groupId = "invoice-generator-stock-deduction")
  public void onEvent(IntegrationEvent event, Acknowledgment ack) {
    if (idempotencyStore.alreadyProcessed(InvoiceTopics.STOCK_DEDUCTION, event.eventId())) {
      log.info(
          "dedupe stock deduction event eventId={} invoiceId={}",
          event.eventId(),
          event.invoiceId());
      ack.acknowledge();
      return;
    }
    log.debug(
        "consuming stock deduction event eventId={} invoiceId={}",
        event.eventId(),
        event.invoiceId());
    stockPort.sendInvoiceForStockDeduction(event.invoice());
    idempotencyStore.markProcessed(InvoiceTopics.STOCK_DEDUCTION, event.eventId());
    ack.acknowledge();
  }
}
