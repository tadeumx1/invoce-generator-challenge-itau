package br.com.itau.invoicegenerator.service.impl;

import br.com.itau.invoicegenerator.model.Address;
import br.com.itau.invoicegenerator.model.AddressPurpose;
import br.com.itau.invoicegenerator.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.model.Invoice;
import br.com.itau.invoicegenerator.model.InvoiceItem;
import br.com.itau.invoicegenerator.model.Order;
import br.com.itau.invoicegenerator.model.PersonType;
import br.com.itau.invoicegenerator.model.Recipient;
import br.com.itau.invoicegenerator.model.Region;
import br.com.itau.invoicegenerator.service.InvoiceGeneratorService;
import br.com.itau.invoicegenerator.service.ProductTaxRateCalculator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InvoiceGeneratorServiceImpl implements InvoiceGeneratorService {

  private final ProductTaxRateCalculator taxRateCalculator;

  public InvoiceGeneratorServiceImpl(ProductTaxRateCalculator taxRateCalculator) {
    this.taxRateCalculator = taxRateCalculator;
  }

  @Override
  public Invoice generateInvoice(Order order) {

    Recipient recipient = order.getRecipient();
    PersonType personType = recipient.getPersonType();
    List<InvoiceItem> invoiceItems = new ArrayList<>();

    if (personType == PersonType.FISICA) {
      double totalItemsValue = order.getTotalItemsValue();
      double taxRate;

      if (totalItemsValue < 500) {
        taxRate = 0;
      } else if (totalItemsValue <= 2000) {
        taxRate = 0.12;
      } else if (totalItemsValue <= 3500) {
        taxRate = 0.15;
      } else {
        taxRate = 0.17;
      }
      invoiceItems = taxRateCalculator.calculateTax(order.getItems(), taxRate);
    } else if (personType == PersonType.JURIDICA) {

      CompanyTaxRegime taxRegime = recipient.getTaxRegime();

      if (taxRegime == CompanyTaxRegime.SIMPLES_NACIONAL) {

        double totalItemsValue = order.getTotalItemsValue();
        double taxRate;

        if (totalItemsValue < 1000) {
          taxRate = 0.03;
        } else if (totalItemsValue <= 2000) {
          taxRate = 0.07;
        } else if (totalItemsValue <= 5000) {
          taxRate = 0.13;
        } else {
          taxRate = 0.19;
        }
        invoiceItems = taxRateCalculator.calculateTax(order.getItems(), taxRate);
      } else if (taxRegime == CompanyTaxRegime.LUCRO_REAL) {
        double totalItemsValue = order.getTotalItemsValue();
        double taxRate;

        if (totalItemsValue < 1000) {
          taxRate = 0.03;
        } else if (totalItemsValue <= 2000) {
          taxRate = 0.09;
        } else if (totalItemsValue <= 5000) {
          taxRate = 0.15;
        } else {
          taxRate = 0.20;
        }
        invoiceItems = taxRateCalculator.calculateTax(order.getItems(), taxRate);
      } else if (taxRegime == CompanyTaxRegime.LUCRO_PRESUMIDO) {
        double totalItemsValue = order.getTotalItemsValue();
        double taxRate;

        if (totalItemsValue < 1000) {
          taxRate = 0.03;
        } else if (totalItemsValue <= 2000) {
          taxRate = 0.09;
        } else if (totalItemsValue <= 5000) {
          taxRate = 0.16;
        } else {
          taxRate = 0.20;
        }
        invoiceItems = taxRateCalculator.calculateTax(order.getItems(), taxRate);
      }
    }

    // Region-specific freight adjustment.
    Region region =
        recipient.getAddresses().stream()
            .filter(
                address ->
                    address.getPurpose() == AddressPurpose.ENTREGA
                        || address.getPurpose() == AddressPurpose.COBRANCA_ENTREGA)
            .map(Address::getRegion)
            .findFirst()
            .orElse(null);

    double freightValue = order.getFreightValue();
    double adjustedFreightValue = 0;

    if (region == Region.NORTE) {
      adjustedFreightValue = freightValue * 1.08;
    } else if (region == Region.NORDESTE) {
      adjustedFreightValue = freightValue * 1.085;
    } else if (region == Region.CENTRO_OESTE) {
      adjustedFreightValue = freightValue * 1.07;
    } else if (region == Region.SUDESTE) {
      adjustedFreightValue = freightValue * 1.048;
    } else if (region == Region.SUL) {
      adjustedFreightValue = freightValue * 1.06;
    }

    // Build the Invoice aggregate.
    String invoiceId = UUID.randomUUID().toString();

    Invoice invoice =
        Invoice.builder()
            .invoiceId(invoiceId)
            .date(LocalDateTime.now())
            .totalItemsValue(order.getTotalItemsValue())
            .freightValue(adjustedFreightValue)
            .items(invoiceItems)
            .recipient(order.getRecipient())
            .build();

    new StockService().sendInvoiceForStockDeduction(invoice);
    new RegistrationService().registerInvoice(invoice);
    new DeliveryService().scheduleDelivery(invoice);
    new FinanceService().sendInvoiceToAccountsReceivable(invoice);

    return invoice;
  }
}
