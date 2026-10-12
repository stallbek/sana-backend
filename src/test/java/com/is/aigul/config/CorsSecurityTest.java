package com.is.aigul.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class CorsSecurityTest {
    @Test void rejectsUnlistedAndWildcardOrigins() throws Exception {
        var filter = new WalletPassCorsConfiguration().apiCorsFilter("").getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/passes");
        request.addHeader("Origin", "https://example.com"); request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThatThrownBy(() -> new WalletPassCorsConfiguration().apiCorsFilter("*"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void explicitlyAllowedOriginCanPreflightTokenHeader() throws Exception {
        var filter = new WalletPassCorsConfiguration().apiCorsFilter("https://example.com").getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/passes");
        request.addHeader("Origin", "https://example.com"); request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type,token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://example.com");
    }
}
