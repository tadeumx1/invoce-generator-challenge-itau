package br.com.itau.invoicegenerator.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.itau.invoicegenerator.testsupport.JwtTestSupport;
import br.com.itau.invoicegenerator.testsupport.NoOpKafkaTestConfig;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StreamUtils;

/**
 * F-DEBUG-LOGS T5 — proves the controller, interactor, tax-bracket, freight, and rejection log
 * lines actually fire on real HTTP traffic, and that MDC carries correlationId on every captured
 * record. Attaches a {@link ListAppender} to the {@code br.com.itau.invoicegenerator} logger at
 * DEBUG so the suite captures both INFO bracket lines and DEBUG decision lines without changing
 * production log levels.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Import({NoOpKafkaTestConfig.class, JwtTestSupport.class})
@TestPropertySource(
    properties = {
      "app.messaging.kafka.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
class DebugLogsIntegrationTest {

  private static final String APPLICATION_LOGGER = "br.com.itau.invoicegenerator";

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTestSupport jwt;

  private ListAppender<ILoggingEvent> appender;
  private ch.qos.logback.classic.Logger applicationLogger;
  private Level previousLevel;

  @BeforeEach
  void attachAppender() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    applicationLogger = context.getLogger(APPLICATION_LOGGER);
    previousLevel = applicationLogger.getLevel();
    applicationLogger.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    applicationLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    applicationLogger.detachAppender(appender);
    applicationLogger.setLevel(previousLevel);
  }

  @Test
  void successfulRequestEmitsBracketingAndDecisionLogs() throws Exception {
    String body = loadFixture("payloads/teste-pf.json");

    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    assertThat(messages()).anyMatch(m -> m.startsWith("invoice request received"));
    assertThat(messages()).anyMatch(m -> m.startsWith("invoice request completed"));
    assertThat(messages()).anyMatch(m -> m.startsWith("invoice generation begin"));
    assertThat(messages()).anyMatch(m -> m.startsWith("invoice generation complete"));
    assertThat(messages()).anyMatch(m -> m.startsWith("tax bracket selected"));
    assertThat(messages()).anyMatch(m -> m.startsWith("freight calculated"));

    // MDC carries correlationId on every captured record (CorrelationIdFilter populates it).
    assertThat(appender.list)
        .filteredOn(event -> event.getMessage().startsWith("invoice request"))
        .allSatisfy(event -> assertThat(event.getMDCPropertyMap()).containsKey("correlationId"));
  }

  @Test
  void unsupportedTaxRegimeEmitsDomainInfoPlusHandlerWarn() throws Exception {
    String body =
        loadFixture("payloads/teste-pj-simples.json")
            .replace(
                "\"regime_tributacao\": \"SIMPLES_NACIONAL\"", "\"regime_tributacao\": \"OUTROS\"");

    mockMvc
        .perform(
            post("/api/orders/generate-invoice")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());

    assertThat(appender.list)
        .anyMatch(
            event ->
                event.getLevel() == Level.INFO
                    && event.getFormattedMessage().contains("UNSUPPORTED_TAX_REGIME"));
    assertThat(appender.list)
        .anyMatch(
            event ->
                event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("invoice request rejected")
                    && event.getFormattedMessage().contains("UNSUPPORTED_TAX_REGIME"));
  }

  private java.util.List<String> messages() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private String bearerToken() {
    return "Bearer " + jwt.tokenFor("demo", "invoice:write");
  }

  private static String loadFixture(String classpathLocation) throws Exception {
    try (var is = new ClassPathResource(classpathLocation).getInputStream()) {
      return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
    }
  }
}
