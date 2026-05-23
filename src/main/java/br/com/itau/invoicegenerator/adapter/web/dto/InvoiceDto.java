package br.com.itau.invoicegenerator.adapter.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
public class InvoiceDto {

  @JsonProperty("id_nota_fiscal")
  private String invoiceId;

  @JsonProperty("data")
  private LocalDateTime date;

  @JsonProperty("valor_total_itens")
  private BigDecimal totalItemsValue;

  @JsonProperty("valor_frete")
  private BigDecimal freightValue;

  @JsonProperty("itens")
  private List<InvoiceItemDto> items;

  @JsonProperty("destinatario")
  private RecipientDto recipient;
}
