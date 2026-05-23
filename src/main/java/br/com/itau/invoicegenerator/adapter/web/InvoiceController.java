package br.com.itau.invoicegenerator.adapter.web;

import br.com.itau.invoicegenerator.adapter.observability.InvoiceMetricsRecorder;
import br.com.itau.invoicegenerator.adapter.observability.UseCaseObservation;
import br.com.itau.invoicegenerator.adapter.web.dto.InvoiceDto;
import br.com.itau.invoicegenerator.adapter.web.dto.OrderDto;
import br.com.itau.invoicegenerator.adapter.web.dto.WebInvoiceMapper;
import br.com.itau.invoicegenerator.domain.model.Address;
import br.com.itau.invoicegenerator.domain.model.AddressPurpose;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.domain.model.Recipient;
import br.com.itau.invoicegenerator.domain.model.Region;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvoiceController {

  private final UseCaseObservation useCaseObservation;
  private final InvoiceMetricsRecorder metricsRecorder;
  private final WebInvoiceMapper webInvoiceMapper;

  public InvoiceController(
      UseCaseObservation useCaseObservation, InvoiceMetricsRecorder metricsRecorder) {
    this.useCaseObservation = useCaseObservation;
    this.metricsRecorder = metricsRecorder;
    this.webInvoiceMapper = new WebInvoiceMapper();
  }

  @PostMapping({"/api/orders/generate-invoice", "/api/pedido/gerarNotaFiscal"})
  public ResponseEntity<InvoiceDto> generateInvoice(@RequestBody OrderDto request) {
    Order order = webInvoiceMapper.toDomain(request);
    Invoice invoice = useCaseObservation.generate(order);
    Recipient recipient = order.getRecipient();
    metricsRecorder.recordGenerated(
        recipient.getPersonType(),
        recipient.getTaxRegime(),
        deliveryRegion(order),
        order.getItems() == null ? 0 : order.getItems().size());
    return new ResponseEntity<>(webInvoiceMapper.toDto(invoice), HttpStatus.OK);
  }

  /**
   * A successful invoice path implies the freight calculator already validated that a delivery
   * region exists (otherwise it throws {@code INVALID_DELIVERY_REGION}). This helper mirrors the
   * same lookup so the {@code region} tag matches the dimension freight was computed against.
   */
  private static Region deliveryRegion(Order order) {
    return order.getRecipient().getAddresses().stream()
        .filter(InvoiceController::isDeliveryAddress)
        .map(Address::getRegion)
        .filter(region -> region != null)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no delivery region on a successful invoice — freight validation drifted"));
  }

  private static boolean isDeliveryAddress(Address address) {
    return address.getPurpose() == AddressPurpose.ENTREGA
        || address.getPurpose() == AddressPurpose.COBRANCA_ENTREGA;
  }
}
