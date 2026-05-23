package br.com.itau.invoicegenerator.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Recipient {

    @JsonProperty("nome")
    private String name;

    @JsonProperty("tipo_pessoa")
    private PersonType personType;

    @JsonProperty("regime_tributacao")
    private CompanyTaxRegime taxRegime;

    @JsonProperty("documentos")
    private List<Document> documents;

    @JsonProperty("enderecos")
    private List<Address> addresses;
}
