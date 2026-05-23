package br.com.itau.invoicegenerator.adapter.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
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
public class OrderDto {

  @JsonProperty("id_pedido")
  private int orderId;

  @JsonProperty("data")
  private LocalDate date;

  @JsonProperty("valor_total_itens")
  private BigDecimal totalItemsValue;

  @JsonProperty("valor_frete")
  private BigDecimal freightValue;

  @JsonProperty("itens")
  private List<ItemDto> items;

  @JsonProperty("destinatario")
  private RecipientDto recipient;
}
