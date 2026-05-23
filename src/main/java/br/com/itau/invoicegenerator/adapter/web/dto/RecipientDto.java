package br.com.itau.invoicegenerator.adapter.web.dto;

import br.com.itau.invoicegenerator.domain.model.CompanyTaxRegime;
import br.com.itau.invoicegenerator.domain.model.PersonType;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public class RecipientDto {

  @JsonProperty("nome")
  private String name;

  @JsonProperty("tipo_pessoa")
  private PersonType personType;

  @JsonProperty("regime_tributacao")
  private CompanyTaxRegime taxRegime;

  @JsonProperty("documentos")
  private List<DocumentDto> documents;

  @JsonProperty("enderecos")
  private List<AddressDto> addresses;
}
