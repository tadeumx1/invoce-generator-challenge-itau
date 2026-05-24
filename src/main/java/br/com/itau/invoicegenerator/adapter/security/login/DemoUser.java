package br.com.itau.invoicegenerator.adapter.security.login;

/**
 * F-AUTH — demo user record held by {@link InMemoryUserStore}. {@code scopes} is the
 * space-separated string that lands as the JWT {@code scope} claim. Production replaces this with a
 * real user directory (DB / LDAP / IdP).
 */
public record DemoUser(String username, String passwordHash, String scopes) {}
