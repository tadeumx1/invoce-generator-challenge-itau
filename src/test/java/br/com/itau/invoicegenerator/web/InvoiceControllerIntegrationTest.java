package br.com.itau.invoicegenerator.web;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StreamUtils;

/**
 * SAFETY-24, SAFETY-25, SAFETY-26 — end-to-end HTTP integration against the two sample payloads
 * shipped in src/main/resources/paylods/. Proves the JSON contract (snake_case Portuguese keys) is
 * intact through the whole stack.
 *
 * <p>Uses {@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)} so the calculator's singleton-scoped
 * accumulation bug (C-1) doesn't pollute one test with another's items. M2's C-1 fix removes the
 * need for this.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class InvoiceControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void respondsForTestePfPayloadWithExpectedContract() throws Exception {
    String body = loadFixture("paylods/teste-pf.json");

    MvcResult result =
        mockMvc
            .perform(
                post("/api/orders/generate-invoice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            // Portuguese snake_case keys
            .andExpect(jsonPath("$.id_nota_fiscal").value(notNullValue()))
            .andExpect(jsonPath("$.data").value(notNullValue()))
            .andExpect(jsonPath("$.valor_total_itens").value(closeTo(100.0, 1e-6)))
            // 10.0 * 1.048 (SUDESTE)
            .andExpect(jsonPath("$.valor_frete").value(closeTo(10.48, 1e-6)))
            // FISICA, totalItemsValue=100 < 500 → rate 0
            .andExpect(jsonPath("$.itens[0].valor_tributo_item").value(closeTo(0.0, 1e-6)))
            .andExpect(jsonPath("$.destinatario.tipo_pessoa").value(equalTo("FISICA")))
            .andReturn();

    assertNoEnglishKeysAppearInResponse(result.getResponse().getContentAsString());
  }

  @Test
  void respondsForTestePjSimplesPayloadWithExpectedContract() throws Exception {
    String body = loadFixture("paylods/teste-pj-simples.json");

    MvcResult result =
        mockMvc
            .perform(
                post("/api/orders/generate-invoice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.valor_total_itens").value(closeTo(5840.0, 1e-6)))
            // 72.0 * 1.048 (SUDESTE)
            .andExpect(jsonPath("$.valor_frete").value(closeTo(75.456, 1e-6)))
            // JURIDICA + SIMPLES_NACIONAL + totalItemsValue=5840 > 5000 → rate 0.19 → 730 * 0.19 =
            // 138.7
            .andExpect(jsonPath("$.itens[0].valor_tributo_item").value(closeTo(138.7, 1e-6)))
            .andExpect(jsonPath("$.destinatario.tipo_pessoa").value(equalTo("JURIDICA")))
            .andReturn();

    assertNoEnglishKeysAppearInResponse(result.getResponse().getContentAsString());
  }

  private static String loadFixture(String classpathLocation) throws Exception {
    try (var in = new ClassPathResource(classpathLocation).getInputStream()) {
      return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
    }
  }

  /** SAFETY-26: response must use snake_case Portuguese keys exclusively. */
  private static void assertNoEnglishKeysAppearInResponse(String json) {
    String[] forbiddenKeys = {
      "\"invoiceId\"",
      "\"totalItemsValue\"",
      "\"freightValue\"",
      "\"itemTaxValue\"",
      "\"unitPrice\"",
      "\"personType\"",
      "\"taxRegime\""
    };
    for (String key : forbiddenKeys) {
      assertFalse(
          json.contains(key),
          "Response must not contain English key "
              + key
              + " — JSON contract is snake_case Portuguese.");
    }
  }
}
