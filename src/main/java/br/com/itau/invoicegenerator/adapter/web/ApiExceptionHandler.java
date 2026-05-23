package br.com.itau.invoicegenerator.adapter.web;

import br.com.itau.invoicegenerator.adapter.web.dto.ErrorResponseDto;
import br.com.itau.invoicegenerator.domain.exception.InvalidInvoiceOrderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(InvalidInvoiceOrderException.class)
  public ResponseEntity<ErrorResponseDto> handleInvalidInvoiceOrder(
      InvalidInvoiceOrderException exception) {
    ErrorResponseDto body = new ErrorResponseDto(exception.getCode(), exception.getMessage());
    return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
  }
}
