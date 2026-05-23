package br.com.itau.invoicegenerator.adapter.config;

import br.com.itau.invoicegenerator.application.GenerateInvoiceInteractor;
import br.com.itau.invoicegenerator.application.GenerateInvoiceUseCase;
import br.com.itau.invoicegenerator.domain.port.FreightCalculator;
import br.com.itau.invoicegenerator.domain.port.InvoiceSideEffectDispatcher;
import br.com.itau.invoicegenerator.domain.port.TaxRateCalculator;
import br.com.itau.invoicegenerator.domain.service.LegacyFreightCalculator;
import br.com.itau.invoicegenerator.domain.service.LegacyProductTaxRateCalculator;
import br.com.itau.invoicegenerator.domain.service.TaxRateTable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationBeanConfig {

  @Bean
  public TaxRateTable taxRateTable() {
    return new TaxRateTable();
  }

  @Bean
  public TaxRateCalculator taxRateCalculator() {
    return new LegacyProductTaxRateCalculator();
  }

  @Bean
  public FreightCalculator freightCalculator() {
    return new LegacyFreightCalculator();
  }

  @Bean
  public GenerateInvoiceUseCase generateInvoiceUseCase(
      TaxRateTable taxRateTable,
      TaxRateCalculator taxRateCalculator,
      FreightCalculator freightCalculator,
      InvoiceSideEffectDispatcher sideEffectDispatcher) {
    return new GenerateInvoiceInteractor(
        taxRateTable, taxRateCalculator, freightCalculator, sideEffectDispatcher);
  }
}
