package br.com.itau.invoicegenerator.domain.exception;

public class InvalidInvoiceOrderException extends RuntimeException {

  private final String code;

  public InvalidInvoiceOrderException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
