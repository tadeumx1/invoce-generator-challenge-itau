package br.com.itau.invoicegenerator.adapter.web.dto;

import br.com.itau.invoicegenerator.domain.model.DocumentType;
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
public class DocumentDto {

  @JsonProperty("numero")
  private String number;

  @JsonProperty("tipo")
  private DocumentType type;
}
