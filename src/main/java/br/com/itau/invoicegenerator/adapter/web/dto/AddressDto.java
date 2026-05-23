package br.com.itau.invoicegenerator.adapter.web.dto;

import br.com.itau.invoicegenerator.domain.model.AddressPurpose;
import br.com.itau.invoicegenerator.domain.model.Region;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public class AddressDto {

  @JsonProperty("cep")
  private String zipCode;

  @JsonProperty("logradouro")
  private String street;

  @JsonProperty("numero")
  private String number;

  @JsonProperty("estado")
  private String state;

  @JsonProperty("complemento")
  private String complement;

  @JsonProperty("finalidade")
  private AddressPurpose purpose;

  @JsonProperty("regiao")
  private Region region;
}
