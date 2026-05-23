package br.com.itau.invoicegenerator.adapter.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void usesIncomingHeaderWhenItMatchesTheAllowedShape() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER_NAME, "probe-abc_123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    MdcCapturingChain chain = new MdcCapturingChain();
    filter.doFilter(request, response, chain);

    assertEquals("probe-abc_123", chain.observedCorrelationId);
    assertEquals("probe-abc_123", response.getHeader(CorrelationIdFilter.HEADER_NAME));
    assertNull(MDC.get(CorrelationIdFilter.MDC_KEY), "MDC must be cleared after the chain");
  }

  @Test
  void generatesUuidWhenHeaderIsMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    MdcCapturingChain chain = new MdcCapturingChain();
    filter.doFilter(request, response, chain);

    String observed = chain.observedCorrelationId;
    assertNotNull(observed, "filter must populate MDC with a generated id");
    assertEquals(observed, response.getHeader(CorrelationIdFilter.HEADER_NAME));
    assertEquals(36, observed.length(), "expected a UUID-shaped value");
  }

  @Test
  void rejectsMalformedHeaderAndGeneratesUuid() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER_NAME, "bad value with spaces");
    MockHttpServletResponse response = new MockHttpServletResponse();

    MdcCapturingChain chain = new MdcCapturingChain();
    filter.doFilter(request, response, chain);

    String observed = chain.observedCorrelationId;
    assertNotNull(observed);
    assertEquals(observed, response.getHeader(CorrelationIdFilter.HEADER_NAME));
    assertEquals(
        36, observed.length(), "malformed header must be replaced by a fresh UUID, not echoed");
  }

  private static final class MdcCapturingChain extends MockFilterChain implements FilterChain {

    String observedCorrelationId;

    @Override
    public void doFilter(
        jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
      observedCorrelationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    }

    // Unused but satisfies MockFilterChain extension.
    @SuppressWarnings("unused")
    void noop(HttpServletRequest req, HttpServletResponse res) {}
  }
}
