package br.com.itau.invoicegenerator.adapter.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * F-API-DOCS — OpenAPI 3 document customisation. Declares the {@code bearer-jwt} HTTP security
 * scheme that mirrors the F-AUTH contract (HS256 JWT, 60-minute expiry, scope {@code
 * invoice:write}). The scheme is required at the document level so Swagger UI's "Authorize" button
 * pre-attaches {@code Authorization: Bearer <token>} to every operation; the login endpoint opts
 * out via {@code @SecurityRequirements({})} on the controller method.
 *
 * <p>Light customisation only — info block + server + security scheme. Per-field DTO documentation
 * (@Schema(description=...)) is intentionally absent: the JSON contract is frozen in
 * docs/business-rules.md and that file is the SSOT.
 */
@Configuration
public class OpenAPIConfig {

  private static final String BEARER_JWT_SCHEME = "bearer-jwt";

  @Bean
  public OpenAPI invoiceGeneratorOpenAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Invoice Generator API")
                .version("0.0.1-SNAPSHOT")
                .description(
                    "Brazilian invoice generator — calculates ICMS by person type / tax regime"
                        + " / item value, computes freight by delivery region, and dispatches"
                        + " four side-effect events (stock, registration, delivery, accounts"
                        + " receivable) through Kafka. Protected endpoints require an HS256 JWT"
                        + " obtained from POST /api/auth/login - demo users: demo/demo123,"
                        + " admin/admin123")
                .contact(new Contact().name("Invoice Generator Team")))
        .addServersItem(new Server().url("http://localhost:8080").description("Local development"))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_JWT_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "HS256 JWT issued by POST /api/auth/login. Required scope:"
                                + " invoice:write.")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_JWT_SCHEME));
  }
}
