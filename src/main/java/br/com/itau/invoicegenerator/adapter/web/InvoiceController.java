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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvoiceController {

  private static final Logger LOG = LoggerFactory.getLogger(InvoiceController.class);

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
  @Operation(
      summary = "Calculate an invoice and dispatch downstream side effects",
      description =
          "Synchronously computes ICMS + freight for the order and publishes four Kafka events"
              + " (stock deduction, invoice registration, delivery scheduling, accounts"
              + " receivable). HTTP 200 means \"invoice calculated and dispatch accepted\", NOT"
              + " \"all downstreams completed\". Requires JWT scope invoice:write."
              + " /api/pedido/gerarNotaFiscal is the preserved legacy alias.")
  @SecurityRequirement(name = "bearer-jwt")
  public ResponseEntity<InvoiceDto> generateInvoice(@RequestBody OrderDto request) {
    long startNanos = System.nanoTime();
    Order order = webInvoiceMapper.toDomain(request);
    int itemCount = order.getItems() == null ? 0 : order.getItems().size();
    LOG.info(
        "invoice request received orderId={} itemCount={} personType={} taxRegime={}",
        order.getOrderId(),
        itemCount,
        order.getRecipient().getPersonType(),
        order.getRecipient().getTaxRegime());
    Invoice invoice = useCaseObservation.generate(order);
    Recipient recipient = order.getRecipient();
    metricsRecorder.recordGenerated(
        recipient.getPersonType(), recipient.getTaxRegime(), deliveryRegion(order), itemCount);
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    LOG.info(
        "invoice request completed orderId={} invoiceId={} status=200 elapsedMs={}",
        order.getOrderId(),
        invoice.getInvoiceId(),
        elapsedMs);
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
