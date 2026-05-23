package br.com.itau.invoicegenerator.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IdempotencyStoreTest {

  @Test
  void newEventIsNotYetProcessed() {
    IdempotencyStore store = new IdempotencyStore();
    assertFalse(store.alreadyProcessed(InvoiceTopics.STOCK_DEDUCTION, "evt-1"));
  }

  @Test
  void markProcessedMakesAlreadyProcessedTrue() {
    IdempotencyStore store = new IdempotencyStore();
    store.markProcessed(InvoiceTopics.INVOICE_REGISTRATION, "evt-2");
    assertTrue(store.alreadyProcessed(InvoiceTopics.INVOICE_REGISTRATION, "evt-2"));
  }

  @Test
  void sameEventIdOnDifferentTopicsAreIndependent() {
    IdempotencyStore store = new IdempotencyStore();
    store.markProcessed(InvoiceTopics.STOCK_DEDUCTION, "shared-evt");
    assertTrue(store.alreadyProcessed(InvoiceTopics.STOCK_DEDUCTION, "shared-evt"));
    assertFalse(store.alreadyProcessed(InvoiceTopics.DELIVERY_SCHEDULING, "shared-evt"));
  }

  @Test
  void doubleMarkIsIdempotentInTheStoreItself() {
    IdempotencyStore store = new IdempotencyStore();
    store.markProcessed(InvoiceTopics.ACCOUNTS_RECEIVABLE, "evt-3");
    store.markProcessed(InvoiceTopics.ACCOUNTS_RECEIVABLE, "evt-3");
    assertEquals(1, store.size());
  }
}
