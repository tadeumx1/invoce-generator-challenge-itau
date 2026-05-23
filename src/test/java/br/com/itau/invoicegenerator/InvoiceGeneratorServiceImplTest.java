package br.com.itau.invoicegenerator;

import br.com.itau.invoicegenerator.model.*;
import br.com.itau.invoicegenerator.service.ProductTaxRateCalculator;
import br.com.itau.invoicegenerator.service.impl.InvoiceGeneratorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InvoiceGeneratorServiceImplTest {

    @InjectMocks
    private InvoiceGeneratorServiceImpl invoiceGeneratorService;

    @Mock
    private ProductTaxRateCalculator productTaxRateCalculator;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void shouldGenerateInvoiceForPersonTypeFisicaWithTotalItemsValueLessThan500() {
        Order order = new Order();
        order.setTotalItemsValue(400);
        order.setFreightValue(100);
        Recipient recipient = new Recipient();
        recipient.setPersonType(PersonType.FISICA);

        Address address = new Address();
        address.setPurpose(AddressPurpose.ENTREGA);
        address.setRegion(Region.SUDESTE);
        recipient.setAddresses(Arrays.asList(address));

        order.setRecipient(recipient);

        Item item = new Item();
        item.setUnitPrice(100);
        item.setQuantity(4);
        order.setItems(Arrays.asList(item));

        Invoice invoice = invoiceGeneratorService.generateInvoice(order);

        assertEquals(order.getTotalItemsValue(), invoice.getTotalItemsValue());
        assertEquals(1, invoice.getItems().size());
        assertEquals(0, invoice.getItems().get(0).getItemTaxValue());
    }

    @Test
    public void shouldGenerateInvoiceForPersonTypeJuridicaWithLucroPresumidoAndTotalItemsValueGreaterThan5000() {
        Order order = new Order();
        order.setTotalItemsValue(6000);
        order.setFreightValue(100);
        Recipient recipient = new Recipient();
        recipient.setPersonType(PersonType.JURIDICA);
        recipient.setTaxRegime(CompanyTaxRegime.LUCRO_PRESUMIDO);

        Address address = new Address();
        address.setPurpose(AddressPurpose.ENTREGA);
        address.setRegion(Region.SUDESTE);
        recipient.setAddresses(Arrays.asList(address));

        order.setRecipient(recipient);

        Item item = new Item();
        item.setUnitPrice(1000);
        item.setQuantity(6);
        order.setItems(Arrays.asList(item));

        Invoice invoice = invoiceGeneratorService.generateInvoice(order);

        assertEquals(order.getTotalItemsValue(), invoice.getTotalItemsValue());
        assertEquals(1, invoice.getItems().size());
        assertEquals(0.20 * item.getUnitPrice(), invoice.getItems().get(0).getItemTaxValue());
    }
}
