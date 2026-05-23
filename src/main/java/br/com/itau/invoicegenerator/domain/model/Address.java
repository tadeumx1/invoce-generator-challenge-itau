package br.com.itau.invoicegenerator.domain.model;

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
  private String zipCode;
  private String street;
  private String number;
  private String state;
  private String complement;
  private AddressPurpose purpose;
  private Region region;
}
