package br.com.itau.invoicegenerator.adapter.security.login;

/**
 * F-AUTH — {@code POST /api/auth/login} request body. English field names (this is the
 * auth-protocol surface, not the frozen Portuguese invoice contract).
 */
public record LoginRequest(String username, String password) {}
