package br.com.itau.invoicegenerator.adapter.web;

import br.com.itau.invoicegenerator.adapter.web.dto.InvoiceDto;
import br.com.itau.invoicegenerator.adapter.web.dto.OrderDto;
import br.com.itau.invoicegenerator.adapter.web.dto.WebInvoiceMapper;
import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvoiceController {

  private final GenerateInvoiceUseCase generateInvoiceUseCase;
  private final WebInvoiceMapper webInvoiceMapper;

  public InvoiceController(GenerateInvoiceUseCase generateInvoiceUseCase) {
    this.generateInvoiceUseCase = generateInvoiceUseCase;
    this.webInvoiceMapper = new WebInvoiceMapper();
  }

  @PostMapping({"/api/orders/generate-invoice", "/api/pedido/gerarNotaFiscal"})
  public ResponseEntity<InvoiceDto> generateInvoice(@RequestBody OrderDto request) {
    Order order = webInvoiceMapper.toDomain(request);
    Invoice invoice = generateInvoiceUseCase.generateInvoice(order);
    return new ResponseEntity<>(webInvoiceMapper.toDto(invoice), HttpStatus.OK);
  }
}
