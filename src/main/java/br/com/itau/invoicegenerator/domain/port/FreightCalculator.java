package br.com.itau.invoicegenerator.domain.port;

import br.com.itau.invoicegenerator.domain.model.Order;
import java.math.BigDecimal;

public interface FreightCalculator {

  BigDecimal calculateFreight(Order order);
}
