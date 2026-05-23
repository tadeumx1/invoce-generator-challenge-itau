package br.com.itau.invoicegenerator.domain.service;

import br.com.itau.invoicegenerator.domain.exception.InvalidInvoiceOrderException;
import br.com.itau.invoicegenerator.domain.model.Address;
import br.com.itau.invoicegenerator.domain.model.AddressPurpose;
import br.com.itau.invoicegenerator.domain.model.Money;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.domain.model.Region;
import br.com.itau.invoicegenerator.domain.port.FreightCalculator;
import java.math.BigDecimal;
import java.util.Optional;

public class LegacyFreightCalculator implements FreightCalculator {

  @Override
  public BigDecimal calculateFreight(Order order) {
    Region region =
        findDeliveryRegion(order)
            .orElseThrow(
                () ->
                    new InvalidInvoiceOrderException(
                        "INVALID_DELIVERY_REGION",
                        "A delivery address with region is required to calculate freight."));

    BigDecimal freightValue = order.getFreightValue();
    BigDecimal adjustedFreight =
        switch (region) {
          case NORTE -> freightValue.multiply(new BigDecimal("1.08"));
          case NORDESTE -> freightValue.multiply(new BigDecimal("1.085"));
          case CENTRO_OESTE -> freightValue.multiply(new BigDecimal("1.07"));
          case SUDESTE -> freightValue.multiply(new BigDecimal("1.048"));
          case SUL -> freightValue.multiply(new BigDecimal("1.06"));
        };
    return Money.rounded(adjustedFreight);
  }

  private Optional<Region> findDeliveryRegion(Order order) {
    return order.getRecipient().getAddresses().stream()
        .filter(this::isDeliveryAddress)
        .findFirst()
        .map(Address::getRegion)
        .filter(region -> region != null);
  }

  private boolean isDeliveryAddress(Address address) {
    return address.getPurpose() == AddressPurpose.ENTREGA
        || address.getPurpose() == AddressPurpose.COBRANCA_ENTREGA;
  }
}
