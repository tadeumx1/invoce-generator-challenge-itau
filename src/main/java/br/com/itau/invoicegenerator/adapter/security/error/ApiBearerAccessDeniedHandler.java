package br.com.itau.invoicegenerator.adapter.security.error;

import br.com.itau.invoicegenerator.adapter.web.dto.ErrorResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * F-AUTH — emits the standard {@code {codigo, mensagem}} envelope on 403 (e.g., authenticated user
 * is missing the required {@code SCOPE_invoice:write} authority).
 */
@Component
public class ApiBearerAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  public ApiBearerAccessDeniedHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        new ErrorResponseDto("FORBIDDEN", "insufficient scope for this operation"));
  }
}
