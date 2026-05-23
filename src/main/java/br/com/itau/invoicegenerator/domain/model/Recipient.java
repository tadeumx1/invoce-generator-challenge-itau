package br.com.itau.invoicegenerator.domain.model;

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
  private String name;
  private PersonType personType;
  private CompanyTaxRegime taxRegime;
  private List<Document> documents;
  private List<Address> addresses;
}
