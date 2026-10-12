package com.is.aigul.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void ignoresForwardedHeaderEvenFromLoopbackPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.8");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void ignoresForwardedHeaderFromNonLoopbackPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void supportsIpv6LoopbackAndFallsBackWhenHeaderIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("::1");

        assertThat(resolver.resolve(request)).isEqualTo("::1");
    }
}
