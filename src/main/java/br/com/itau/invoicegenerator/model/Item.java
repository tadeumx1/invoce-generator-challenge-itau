package br.com.itau.invoicegenerator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
public class Item {

    @JsonProperty("id_item")
    private String itemId;

    @JsonProperty("descricao")
    private String description;

    @JsonProperty("valor_unitario")
    private double unitPrice;

    @JsonProperty("quantidade")
    private int quantity;
}
