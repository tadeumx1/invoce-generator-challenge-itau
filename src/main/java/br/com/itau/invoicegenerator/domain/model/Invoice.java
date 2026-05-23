package br.com.itau.invoicegenerator.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
public class Invoice {
  private String invoiceId;
  private LocalDateTime date;
  private BigDecimal totalItemsValue;
  private BigDecimal freightValue;
  private List<InvoiceItem> items;
  private Recipient recipient;
}
