package br.com.itau.invoicegenerator.adapter.security.login;

import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * F-AUTH — hardcoded two-user store, mirroring the demo-grade {@code IdempotencyStore} (AD-024)
 * pattern: in-memory for the technical challenge, swap for a durable user directory in production.
 *
 * <p>Passwords are hashed at bean construction using the injected {@link PasswordEncoder} so the
 * source code never carries plaintext nor a literal BCrypt hash that would rot if the cost factor
 * changes.
 */
@Component
public class InMemoryUserStore {

  private final Map<String, DemoUser> users;

  public InMemoryUserStore(PasswordEncoder passwordEncoder) {
    this.users =
        Map.of(
            "demo", new DemoUser("demo", passwordEncoder.encode("demo123"), "invoice:write"),
            "admin",
                new DemoUser(
                    "admin", passwordEncoder.encode("admin123"), "invoice:write invoice:admin"));
  }

  public Optional<DemoUser> findByUsername(String username) {
    return Optional.ofNullable(users.get(username));
  }
}
