package br.com.itau.invoicegenerator.web.controller;

import br.com.itau.invoicegenerator.model.Invoice;
import br.com.itau.invoicegenerator.model.Order;
import br.com.itau.invoicegenerator.service.InvoiceGeneratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class InvoiceController {

  @Autowired private InvoiceGeneratorService invoiceGeneratorService;

  @PostMapping("/generate-invoice")
  public ResponseEntity<Invoice> generateInvoice(@RequestBody Order order) {
    Invoice invoice = invoiceGeneratorService.generateInvoice(order);
    return new ResponseEntity<>(invoice, HttpStatus.OK);
  }
}
