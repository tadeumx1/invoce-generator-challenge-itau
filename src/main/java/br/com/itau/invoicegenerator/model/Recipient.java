package br.com.itau.invoicegenerator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
