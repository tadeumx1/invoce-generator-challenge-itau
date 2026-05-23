package br.com.itau.invoicegenerator.adapter.observability;

import org.springframework.context.annotation.Configuration;

/**
 * Shell {@link Configuration} class that owns the F-OBSERVABILITY beans. T1 just declares the class
 * so subsequent tasks (T2 — filter registration, T3 — metric customizers, T4 — tracing wiring) can
 * attach beans without scattering them across the codebase.
 */
@Configuration
public class ObservabilityConfig {}
