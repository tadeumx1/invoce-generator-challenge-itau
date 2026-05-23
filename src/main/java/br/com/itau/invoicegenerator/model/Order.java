package br.com.itau.invoicegenerator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
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
public class Order {

  @JsonProperty("id_pedido")
  private int orderId;

  @JsonProperty("data")
  private LocalDate date;

  @JsonProperty("valor_total_itens")
  private double totalItemsValue;

  @JsonProperty("valor_frete")
  private double freightValue;

  @JsonProperty("itens")
  private List<Item> items;

  @JsonProperty("destinatario")
  private Recipient recipient;
}
