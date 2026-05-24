package br.com.itau.invoicegenerator.adapter.security.login;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F-AUTH — issues JWTs for the two demo users. Public endpoint by definition: T3 wires {@code
 * .requestMatchers(POST, "/api/auth/login").permitAll()} on the filter chain.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final JwtIssuer jwtIssuer;

  public AuthController(JwtIssuer jwtIssuer) {
    this.jwtIssuer = jwtIssuer;
  }

  @PostMapping("/login")
  @Operation(
      summary = "Issue a JWT for an in-memory demo user",
      description =
          "Validates username + password against the demo InMemoryUserStore (BCrypt-hashed) and"
              + " returns an OAuth2-shaped HS256 JWT with the user's scope. F-RATELIMIT throttles"
              + " this endpoint per IP (5 attempts / 60s). Demo users: demo/demo123 (scope"
              + " invoice:write), admin/admin123 (scope invoice:write invoice:admin).")
  @SecurityRequirements // override: this endpoint issues tokens, no bearer required
  public LoginResponse login(@RequestBody(required = false) LoginRequest request) {
    if (request == null || isBlank(request.username()) || isBlank(request.password())) {
      throw new InvalidLoginPayloadException("username and password are required");
    }
    return jwtIssuer.issueToken(request.username(), request.password());
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
