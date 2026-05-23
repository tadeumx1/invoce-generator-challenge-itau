package br.com.itau.invoicegenerator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Address {
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
