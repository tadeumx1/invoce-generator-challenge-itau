package br.com.itau.invoicegenerator.adapter.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

  private final ClientIpResolver resolver = new ClientIpResolver();

  @Test
  void singleXffHopReturned() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "203.0.113.7");
    request.setRemoteAddr("127.0.0.1");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
  }

  @Test
  void multiHopXffTakesLeftmost() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1, 10.0.0.2");
    request.setRemoteAddr("127.0.0.1");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
  }

  @Test
  void missingXffFallsBackToRemoteAddr() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.42");

    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
  }

  @Test
  void emptyXffFallsBackToRemoteAddr() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "");
    request.setRemoteAddr("198.51.100.42");

    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
  }

  @Test
  void whitespaceXffFallsBackToRemoteAddr() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "   ");
    request.setRemoteAddr("198.51.100.42");

    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
  }

  @Test
  void nullRemoteAddrFallsBackToUnknownSentinel() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(null);

    assertThat(resolver.resolve(request)).isEqualTo(ClientIpResolver.UNKNOWN);
  }

  @Test
  void ipv6XffSingleHop() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "2001:db8::1");
    request.setRemoteAddr("127.0.0.1");

    assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
  }

  @Test
  void normalRemoteAddrWhenNoXff() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.5");

    assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
  }
}
