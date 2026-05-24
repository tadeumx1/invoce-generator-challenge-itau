package br.com.itau.invoicegenerator.adapter.security.login;

/**
 * F-AUTH — thrown by {@link JwtIssuer} when login credentials don't match. {@code
 * ApiExceptionHandler} maps this to HTTP 401 with code {@code INVALID_CREDENTIALS}.
 */
public class InvalidCredentialsException extends RuntimeException {

  public InvalidCredentialsException(String message) {
    super(message);
  }
}
