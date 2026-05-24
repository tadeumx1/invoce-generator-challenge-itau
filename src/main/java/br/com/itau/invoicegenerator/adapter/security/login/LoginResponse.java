package br.com.itau.invoicegenerator.adapter.security.login;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * F-AUTH — {@code POST /api/auth/login} response. Shape matches the OAuth2 token endpoint contract
 * ({@code access_token}, {@code token_type}, {@code expires_in}, {@code scope}) so a future swap to
 * a real IdP is a drop-in replacement for clients.
 */
public record LoginResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("expires_in") long expiresIn,
    String scope) {}
