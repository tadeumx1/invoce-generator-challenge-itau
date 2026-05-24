package br.com.itau.invoicegenerator.testsupport;

import br.com.itau.invoicegenerator.adapter.security.ApiSecurityProperties;
import java.time.Instant;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * F-AUTH test helper. Mints HS256 JWTs through the same {@link JwtEncoder} bean the production
 * filter chain validates against, so integration tests exercise the full
 * decoder→authentication→scope-check path rather than skipping it via {@code addFilters=false} or
 * {@code @WithMockUser}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @Autowired private JwtTestSupport jwt;
 * mockMvc.perform(post("/api/orders/generate-invoice")
 *     .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.tokenFor("demo", "invoice:write"))
 *     .contentType(MediaType.APPLICATION_JSON)
 *     .content(body))
 * }</pre>
 */
@TestComponent
public class JwtTestSupport {

  private final JwtEncoder jwtEncoder;
  private final ApiSecurityProperties properties;

  public JwtTestSupport(JwtEncoder jwtEncoder, ApiSecurityProperties properties) {
    this.jwtEncoder = jwtEncoder;
    this.properties = properties;
  }

  /** Token valid now → 1 hour, with the given space-separated scopes. */
  public String tokenFor(String subject, String scopes) {
    Instant now = Instant.now();
    return mint(subject, scopes, now, now.plus(properties.expiry()));
  }

  /** Token already expired by {@code -1 minute}. For testing the 401-on-expiry path. */
  public String expiredTokenFor(String subject, String scopes) {
    Instant past = Instant.now().minusSeconds(3600);
    return mint(subject, scopes, past, past.plusSeconds(60));
  }

  private String mint(String subject, String scopes, Instant issuedAt, Instant expiresAt) {
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .subject(subject)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("scope", scopes)
            .build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
