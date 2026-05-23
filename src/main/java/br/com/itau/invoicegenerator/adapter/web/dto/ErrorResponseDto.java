package br.com.itau.invoicegenerator.adapter.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ErrorResponseDto {

  @JsonProperty("codigo")
  private String code;

  @JsonProperty("mensagem")
  private String message;
}
