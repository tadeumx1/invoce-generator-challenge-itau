package br.com.itau.invoicegenerator.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
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
public class Order {
  private int orderId;
  private LocalDate date;
  private BigDecimal totalItemsValue;
  private BigDecimal freightValue;
  private List<Item> items;
  private Recipient recipient;
}
