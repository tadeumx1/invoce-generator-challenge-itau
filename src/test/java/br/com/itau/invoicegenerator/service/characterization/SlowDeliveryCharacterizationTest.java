package br.com.itau.invoicegenerator.service.characterization;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.model.Item;
import br.com.itau.invoicegenerator.domain.model.Money;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.domain.model.PersonType;
import br.com.itau.invoicegenerator.domain.model.Recipient;
import br.com.itau.invoicegenerator.domain.model.Region;
import br.com.itau.invoicegenerator.domain.service.LegacyProductTaxRateCalculator;
import br.com.itau.invoicegenerator.testsupport.Addresses;
import br.com.itau.invoicegenerator.testsupport.Items;
import br.com.itau.invoicegenerator.testsupport.TestUseCases;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SAFETY-23 — characterization of defect C-6 (DeliveryIntegrationPort sleeps an extra 5s when the
 * order has more than 5 items). See docs/business-rules.md §6 and .specs/codebase/CONCERNS.md C-6.
 *
 * <p>Tagged @Tag("slow") so it's excluded from the default surefire profile. Run on demand with
 * {@code ./mvnw test -Dgroups=slow}. M2 (F-DEFECTS-PERFORMANCE) will make this test pass with a
 * bounded latency budget once the delivery dispatch moves async.
 */
class SlowDeliveryCharacterizationTest {

  @Test
  @Tag("slow")
  void ordersWithMoreThanFiveItemsBlockAtLeastFiveSecondsToday_C6() {
    LegacyProductTaxRateCalculator calculator = new LegacyProductTaxRateCalculator();
    GenerateInvoiceUseCase service =
        TestUseCases.generateInvoiceUseCaseWithRealAdapters(calculator);

    Recipient recipient =
        Recipient.builder()
            .personType(PersonType.FISICA)
            .addresses(List.of(Addresses.entrega(Region.SUDESTE)))
            .build();
    List<Item> sixItems =
        List.of(
            Items.item("a", 10, 1),
            Items.item("b", 10, 1),
            Items.item("c", 10, 1),
            Items.item("d", 10, 1),
            Items.item("e", 10, 1),
            Items.item("f", 10, 1));
    Order order =
        Order.builder()
            .orderId(1)
            .totalItemsValue(Money.of(60.0))
            .freightValue(Money.of(10.0))
            .items(sixItems)
            .recipient(recipient)
            .build();

    Instant start = Instant.now();
    service.generateInvoice(order);
    long elapsedMs = Duration.between(start, Instant.now()).toMillis();

    assertTrue(
        elapsedMs >= 5000,
        "C-6: invoices with >5 items must currently take >=5s; observed " + elapsedMs + "ms");
  }
}
