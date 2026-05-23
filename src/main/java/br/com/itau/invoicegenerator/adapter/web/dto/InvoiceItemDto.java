package br.com.itau.invoicegenerator.adapter.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Builder
@AllArgsConstructor
@Getter
@Setter
@NoArgsConstructor
public class InvoiceItemDto {

  @JsonProperty("id_item")
  private String itemId;

  @JsonProperty("descricao")
  private String description;

  @JsonProperty("valor_unitario")
  private BigDecimal unitPrice;

  @JsonProperty("quantidade")
  private int quantity;

  @JsonProperty("valor_tributo_item")
  private BigDecimal itemTaxValue;
}
