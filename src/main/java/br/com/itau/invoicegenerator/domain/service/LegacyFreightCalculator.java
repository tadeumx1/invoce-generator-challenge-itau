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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LegacyFreightCalculator implements FreightCalculator {

  private static final Logger log = LoggerFactory.getLogger(LegacyFreightCalculator.class);

  @Override
  public BigDecimal calculateFreight(Order order) {
    Region region =
        findDeliveryRegion(order)
            .orElseThrow(
                () -> {
                  log.info(
                      "freight rejected codigo=INVALID_DELIVERY_REGION reason=missing-or-null-region");
                  return new InvalidInvoiceOrderException(
                      "INVALID_DELIVERY_REGION",
                      "A delivery address with region is required to calculate freight.");
                });

    BigDecimal freightValue = order.getFreightValue();
    BigDecimal multiplier =
        switch (region) {
          case NORTE -> new BigDecimal("1.08");
          case NORDESTE -> new BigDecimal("1.085");
          case CENTRO_OESTE -> new BigDecimal("1.07");
          case SUDESTE -> new BigDecimal("1.048");
          case SUL -> new BigDecimal("1.06");
        };
    BigDecimal adjusted = Money.rounded(freightValue.multiply(multiplier));
    log.debug(
        "freight calculated region={} baseFreight={} multiplier={} adjustedFreight={}",
        region,
        freightValue.toPlainString(),
        multiplier.toPlainString(),
        adjusted.toPlainString());
    return adjusted;
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
