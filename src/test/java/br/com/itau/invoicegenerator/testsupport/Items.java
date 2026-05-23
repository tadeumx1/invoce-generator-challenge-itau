package br.com.itau.invoicegenerator.testsupport;

import br.com.itau.invoicegenerator.model.Item;

public final class Items {

    private Items() {}

    public static Item item(double unitPrice, int quantity) {
        return new Item("item-1", "Sample item", unitPrice, quantity);
    }

    public static Item item(String id, double unitPrice, int quantity) {
        return new Item(id, "Sample " + id, unitPrice, quantity);
    }
}
