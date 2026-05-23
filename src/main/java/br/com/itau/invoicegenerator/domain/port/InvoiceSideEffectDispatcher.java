package br.com.itau.invoicegenerator.domain.port;

import br.com.itau.invoicegenerator.domain.model.Invoice;

/**
 * Dispatches the four downstream side effects that follow a generated invoice (stock deduction,
 * fiscal registration, delivery scheduling, accounts receivable).
 *
 * <p>The implementation in the adapter layer publishes Kafka integration events; consumers call the
 * existing outbound ports. Domain stays unaware of the transport.
 */
public interface InvoiceSideEffectDispatcher {

  void dispatch(Invoice invoice);
}
