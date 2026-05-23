package br.com.itau.invoicegenerator.domain.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class InvoiceItem {
  private String itemId;
  private String description;
  private BigDecimal unitPrice;
  private int quantity;
  private BigDecimal itemTaxValue;
}
