package br.com.itau.invoicegenerator.testsupport;

import br.com.itau.invoicegenerator.model.Address;
import br.com.itau.invoicegenerator.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.model.Item;
import br.com.itau.invoicegenerator.model.Order;
import br.com.itau.invoicegenerator.model.PersonType;
import br.com.itau.invoicegenerator.model.Recipient;
import br.com.itau.invoicegenerator.model.Region;
import java.util.List;

public final class Orders {

  private Orders() {}

  public static Order fisica(double totalItemsValue) {
    return fisica(totalItemsValue, defaultDeliveryAddress());
  }

  public static Order fisica(double totalItemsValue, Address... addresses) {
    Recipient recipient =
        Recipient.builder().personType(PersonType.FISICA).addresses(List.of(addresses)).build();
    return baseOrder(totalItemsValue, recipient);
  }

  public static Order juridica(double totalItemsValue, CompanyTaxRegime taxRegime) {
    return juridica(totalItemsValue, taxRegime, defaultDeliveryAddress());
  }

  public static Order juridica(
      double totalItemsValue, CompanyTaxRegime taxRegime, Address... addresses) {
    Recipient recipient =
        Recipient.builder()
            .personType(PersonType.JURIDICA)
            .taxRegime(taxRegime)
            .addresses(List.of(addresses))
            .build();
    return baseOrder(totalItemsValue, recipient);
  }

  private static Order baseOrder(double totalItemsValue, Recipient recipient) {
    Item item = Items.item(totalItemsValue, 1);
    return Order.builder()
        .orderId(1)
        .totalItemsValue(totalItemsValue)
        .freightValue(100.0)
        .items(List.of(item))
        .recipient(recipient)
        .build();
  }

  private static Address defaultDeliveryAddress() {
    return Addresses.entrega(Region.SUDESTE);
  }
}
