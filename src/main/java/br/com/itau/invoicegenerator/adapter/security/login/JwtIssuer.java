package br.com.itau.invoicegenerator.adapter.security.login;

import br.com.itau.invoicegenerator.adapter.security.ApiSecurityProperties;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * F-AUTH — verifies username + password against {@link InMemoryUserStore}, then mints a HS256 JWT
 * with {@code iss} / {@code sub} / {@code scope} / {@code iat} / {@code exp} claims. Spring
 * Security's default {@code JwtAuthenticationConverter} maps the space-separated {@code scope}
 * claim to {@code SCOPE_*} authorities on the validating side (see T3).
 */
@Service
public class JwtIssuer {

  private final JwtEncoder jwtEncoder;
  private final ApiSecurityProperties properties;
  private final InMemoryUserStore userStore;
  private final PasswordEncoder passwordEncoder;

  public JwtIssuer(
      JwtEncoder jwtEncoder,
      ApiSecurityProperties properties,
      InMemoryUserStore userStore,
      PasswordEncoder passwordEncoder) {
    this.jwtEncoder = jwtEncoder;
    this.properties = properties;
    this.userStore = userStore;
    this.passwordEncoder = passwordEncoder;
  }

  public LoginResponse issueToken(String username, String rawPassword) {
    DemoUser user =
        userStore
            .findByUsername(username)
            .filter(u -> passwordEncoder.matches(rawPassword, u.passwordHash()))
            .orElseThrow(
                () -> new InvalidCredentialsException("username or password is incorrect"));

    Instant now = Instant.now();
    Instant expiry = now.plus(properties.expiry());

    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .subject(user.username())
            .issuedAt(now)
            .expiresAt(expiry)
            .claim("scope", user.scopes())
            .build();

    String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new LoginResponse(token, "Bearer", properties.expiry().toSeconds(), user.scopes());
  }
}
