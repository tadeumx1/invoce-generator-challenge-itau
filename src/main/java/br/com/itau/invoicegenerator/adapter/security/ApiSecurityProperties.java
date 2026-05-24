package br.com.itau.invoicegenerator.adapter.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * F-AUTH — JWT signing configuration bound to {@code app.security.jwt.*}. Secret is HS256-symmetric
 * (≥ 32 bytes required); production should rotate via the {@code SECURITY_JWT_SECRET} env var and
 * eventually move to RS256 + JWKS (see docs/auth-strategy.md).
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record ApiSecurityProperties(String secret, String issuer, Duration expiry) {}
