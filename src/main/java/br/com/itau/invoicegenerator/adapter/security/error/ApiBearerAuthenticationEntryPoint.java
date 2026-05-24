package br.com.itau.invoicegenerator.adapter.security.error;

import br.com.itau.invoicegenerator.adapter.web.dto.ErrorResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * F-AUTH — emits the standard {@code {codigo, mensagem}} envelope on 401, replacing Spring
 * Security's default {@code BearerTokenAuthenticationEntryPoint} which writes an empty body.
 */
@Component
public class ApiBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  public ApiBearerAuthenticationEntryPoint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader("WWW-Authenticate", "Bearer");
    objectMapper.writeValue(
        response.getOutputStream(),
        new ErrorResponseDto("UNAUTHORIZED", "authentication required"));
  }
}
