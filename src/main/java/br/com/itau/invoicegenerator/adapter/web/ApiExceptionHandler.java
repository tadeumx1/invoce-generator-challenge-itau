package br.com.itau.invoicegenerator.adapter.web;

import br.com.itau.invoicegenerator.adapter.observability.InvoiceMetricsRecorder;
import br.com.itau.invoicegenerator.adapter.security.login.InvalidCredentialsException;
import br.com.itau.invoicegenerator.adapter.security.login.InvalidLoginPayloadException;
import br.com.itau.invoicegenerator.adapter.web.dto.ErrorResponseDto;
import br.com.itau.invoicegenerator.domain.exception.InvalidInvoiceOrderException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private final InvoiceMetricsRecorder metricsRecorder;

  public ApiExceptionHandler(InvoiceMetricsRecorder metricsRecorder) {
    this.metricsRecorder = metricsRecorder;
  }

  @ExceptionHandler(InvalidInvoiceOrderException.class)
  public ResponseEntity<ErrorResponseDto> handleInvalidInvoiceOrder(
      InvalidInvoiceOrderException exception) {
    metricsRecorder.recordRejected(exception.getCode());
    LOG.warn(
        "invoice request rejected codigo={} status=400 reason={}",
        exception.getCode(),
        exception.getMessage());
    ErrorResponseDto body = new ErrorResponseDto(exception.getCode(), exception.getMessage());
    return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ErrorResponseDto> handleInvalidCredentials(
      InvalidCredentialsException exception) {
    LOG.warn("login rejected codigo=INVALID_CREDENTIALS status=401");
    ErrorResponseDto body = new ErrorResponseDto("INVALID_CREDENTIALS", exception.getMessage());
    return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
  }

  @ExceptionHandler(InvalidLoginPayloadException.class)
  public ResponseEntity<ErrorResponseDto> handleInvalidLoginPayload(
      InvalidLoginPayloadException exception) {
    LOG.warn(
        "login rejected codigo=INVALID_LOGIN_PAYLOAD status=400 reason={}", exception.getMessage());
    ErrorResponseDto body = new ErrorResponseDto("INVALID_LOGIN_PAYLOAD", exception.getMessage());
    return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
  }

  /**
   * F-RATELIMIT T3 — defence-in-depth. The primary 429 path is {@code RateLimitFilter} writing the
   * envelope directly (filter-level rejections never reach {@code DispatcherServlet}). This handler
   * covers any future code that uses {@code @RateLimiter} annotations on controller methods (the
   * AOP path throws {@link RequestNotPermitted}) and emits the same envelope so clients see one
   * contract regardless of which mechanism throttled them.
   */
  @ExceptionHandler(RequestNotPermitted.class)
  public ResponseEntity<ErrorResponseDto> handleRequestNotPermitted(RequestNotPermitted exception) {
    LOG.info(
        "request throttled codigo=RATE_LIMIT_EXCEEDED status=429 reason={}",
        exception.getMessage());
    ErrorResponseDto body =
        new ErrorResponseDto(
            "RATE_LIMIT_EXCEEDED",
            "Limite de requisicoes excedido. Tente novamente em alguns instantes.");
    return new ResponseEntity<>(body, HttpStatus.TOO_MANY_REQUESTS);
  }
}
