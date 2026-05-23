package br.com.itau.invoicegenerator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
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
public class Invoice {

  @JsonProperty("id_nota_fiscal")
  private String invoiceId;

  @JsonProperty("data")
  private LocalDateTime date;

  @JsonProperty("valor_total_itens")
  private double totalItemsValue;

  @JsonProperty("valor_frete")
  private double freightValue;

  @JsonProperty("itens")
  private List<InvoiceItem> items;

  @JsonProperty("destinatario")
  private Recipient recipient;
}
