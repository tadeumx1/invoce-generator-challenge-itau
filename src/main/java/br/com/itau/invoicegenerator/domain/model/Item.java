package br.com.itau.invoicegenerator.domain.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
public class Item {
  private String itemId;
  private String description;
  private BigDecimal unitPrice;
  private int quantity;
}
