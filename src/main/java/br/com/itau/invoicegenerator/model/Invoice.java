package br.com.itau.invoicegenerator.model;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

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
