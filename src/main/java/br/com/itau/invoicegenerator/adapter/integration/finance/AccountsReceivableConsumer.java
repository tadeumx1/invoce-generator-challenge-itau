package br.com.itau.invoicegenerator.adapter.integration.finance;

import br.com.itau.invoicegenerator.adapter.messaging.IdempotencyStore;
import br.com.itau.invoicegenerator.adapter.messaging.IntegrationEvent;
import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.port.AccountsReceivablePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;

/** Consumes accounts-receivable events and invokes {@link AccountsReceivablePort}. */
public class AccountsReceivableConsumer {

  private static final Logger log = LoggerFactory.getLogger(AccountsReceivableConsumer.class);

  private final AccountsReceivablePort accountsReceivablePort;
  private final IdempotencyStore idempotencyStore;

  public AccountsReceivableConsumer(
      AccountsReceivablePort accountsReceivablePort, IdempotencyStore idempotencyStore) {
    this.accountsReceivablePort = accountsReceivablePort;
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
      topics = InvoiceTopics.ACCOUNTS_RECEIVABLE,
      groupId = "invoice-generator-accounts-receivable")
  public void onEvent(IntegrationEvent event, Acknowledgment ack) {
    if (idempotencyStore.alreadyProcessed(InvoiceTopics.ACCOUNTS_RECEIVABLE, event.eventId())) {
      log.info(
          "dedupe accounts receivable event eventId={} invoiceId={}",
          event.eventId(),
          event.invoiceId());
      ack.acknowledge();
      return;
    }
    log.debug(
        "consuming accounts receivable event eventId={} invoiceId={}",
        event.eventId(),
        event.invoiceId());
    accountsReceivablePort.sendInvoiceToAccountsReceivable(event.invoice());
    idempotencyStore.markProcessed(InvoiceTopics.ACCOUNTS_RECEIVABLE, event.eventId());
    ack.acknowledge();
  }
}
