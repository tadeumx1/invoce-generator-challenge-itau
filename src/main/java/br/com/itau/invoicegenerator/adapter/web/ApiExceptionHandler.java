package br.com.itau.invoicegenerator.adapter.web;

import br.com.itau.invoicegenerator.adapter.observability.InvoiceMetricsRecorder;
import br.com.itau.invoicegenerator.adapter.security.login.InvalidCredentialsException;
import br.com.itau.invoicegenerator.adapter.security.login.InvalidLoginPayloadException;
import br.com.itau.invoicegenerator.adapter.web.dto.ErrorResponseDto;
import br.com.itau.invoicegenerator.domain.exception.InvalidInvoiceOrderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  private final InvoiceMetricsRecorder metricsRecorder;

  public ApiExceptionHandler(InvoiceMetricsRecorder metricsRecorder) {
    this.metricsRecorder = metricsRecorder;
  }

  @ExceptionHandler(InvalidInvoiceOrderException.class)
  public ResponseEntity<ErrorResponseDto> handleInvalidInvoiceOrder(
      InvalidInvoiceOrderException exception) {
    metricsRecorder.recordRejected(exception.getCode());
    ErrorResponseDto body = new ErrorResponseDto(exception.getCode(), exception.getMessage());
    return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ErrorResponseDto> handleInvalidCredentials(
      InvalidCredentialsException exception) {
    ErrorResponseDto body = new ErrorResponseDto("INVALID_CREDENTIALS", exception.getMessage());
    return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
  }

  @ExceptionHandler(InvalidLoginPayloadException.class)
  public ResponseEntity<ErrorResponseDto> handleInvalidLoginPayload(
      InvalidLoginPayloadException exception) {
    ErrorResponseDto body = new ErrorResponseDto("INVALID_LOGIN_PAYLOAD", exception.getMessage());
    return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
  }
}
