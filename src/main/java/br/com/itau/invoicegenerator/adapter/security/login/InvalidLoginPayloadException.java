package br.com.itau.invoicegenerator.adapter.security.login;

/**
 * F-AUTH — thrown when {@code POST /api/auth/login} is missing required fields. Mapped to HTTP 400
 * {@code INVALID_LOGIN_PAYLOAD} by {@code ApiExceptionHandler}.
 */
public class InvalidLoginPayloadException extends RuntimeException {

  public InvalidLoginPayloadException(String message) {
    super(message);
  }
}
