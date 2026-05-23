package br.com.itau.invoicegenerator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class InvoiceItem {

  @JsonProperty("id_item")
  private String itemId;

  @JsonProperty("descricao")
  private String description;

  @JsonProperty("valor_unitario")
  private double unitPrice;

  @JsonProperty("quantidade")
  private int quantity;

  @JsonProperty("valor_tributo_item")
  private double itemTaxValue;
}
