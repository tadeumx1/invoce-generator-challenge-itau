package br.com.itau.invoicegenerator.adapter.integration.finance;

import br.com.itau.invoicegenerator.adapter.messaging.IntegrationEvent;
import br.com.itau.invoicegenerator.adapter.messaging.InvoiceTopics;
import br.com.itau.invoicegenerator.domain.port.AccountsReceivablePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** Consumes accounts-receivable events and invokes {@link AccountsReceivablePort}. */
public class AccountsReceivableConsumer {

  private static final Logger log = LoggerFactory.getLogger(AccountsReceivableConsumer.class);

  private final AccountsReceivablePort accountsReceivablePort;

  public AccountsReceivableConsumer(AccountsReceivablePort accountsReceivablePort) {
    this.accountsReceivablePort = accountsReceivablePort;
  }

  @KafkaListener(
      topics = InvoiceTopics.ACCOUNTS_RECEIVABLE,
      groupId = "invoice-generator-accounts-receivable")
  public void onEvent(IntegrationEvent event, Acknowledgment ack) {
    log.debug(
        "consuming accounts receivable event eventId={} invoiceId={}",
        event.eventId(),
        event.invoiceId());
    accountsReceivablePort.sendInvoiceToAccountsReceivable(event.invoice());
    ack.acknowledge();
  }
}
