package br.com.itau.invoicegenerator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Document {

    @JsonProperty("numero")
    private String number;

    @JsonProperty("tipo")
    private DocumentType type;
}