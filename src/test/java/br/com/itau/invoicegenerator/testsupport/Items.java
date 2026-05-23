package br.com.itau.invoicegenerator.testsupport;

import br.com.itau.invoicegenerator.domain.model.Item;
import br.com.itau.invoicegenerator.domain.model.Money;

public final class Items {

  private Items() {}

  public static Item item(double unitPrice, int quantity) {
    return new Item("item-1", "Sample item", Money.of(unitPrice), quantity);
  }

  public static Item item(String id, double unitPrice, int quantity) {
    return new Item(id, "Sample " + id, Money.of(unitPrice), quantity);
  }
}
