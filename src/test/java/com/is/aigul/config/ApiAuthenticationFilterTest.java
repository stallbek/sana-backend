package com.is.aigul.config;

import com.is.aigul.controller.RestExceptionHandler;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.AuthRateLimitService;
import com.is.aigul.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiAuthenticationFilterTest {
    @Test void protectsWalletPrivateAndFutureRoutesWithoutAuthentication() throws Exception {
        AuthService auth = mock(AuthService.class);
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(auth, new AuthRateLimitService(new MutableClock()));
        for (String path : new String[]{"/api/passes", "/api/products", "/api/future-route", "/api/auth/register"}) {
            MockHttpServletRequest req = new MockHttpServletRequest(path.equals("/api/passes") ? "POST" : "GET", path);
            MockHttpServletResponse res = new MockHttpServletResponse(); MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(401); assertThat(chain.getRequest()).isNull();
        }
    }
    @Test void allowsValidHeaderButNeverQueryOrCookieTokens() throws Exception {
        AuthService auth = mock(AuthService.class); when(auth.getUserId("valid")).thenReturn(7L);
        MockMvc mvc = mvc(auth);
        mvc.perform(get("/api/private").header("token", "valid")).andExpect(status().isOk());
        mvc.perform(get("/api/private").param("token", "valid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/private").cookie(new jakarta.servlet.http.Cookie("token", "valid")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/private").header("token", "expired"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void limitsAuthenticationBeforeControllerAndIgnoresSpoofedForwardedHeaders() throws Exception {
        MockMvc mvc = mvc(mock(AuthService.class));
        for (int i=0;i<10;i++) mvc.perform(post("/api/auth/login")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").header("X-Forwarded-For", "192.0.2.123"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        mvc.perform(get("/")).andExpect(status().isOk());
    }
    @Test void blocksOversizedKnownLengthBeforeController() throws Exception {
        AuthService auth = mock(AuthService.class); when(auth.getUserId("valid")).thenReturn(7L);
        for (String contentType : new String[]{"application/json", "application/problem+json", "text/plain"}) {
            mvc(auth).perform(post("/api/private").header("token", "valid").contentType(contentType)
                    .content("x".repeat(1024*1024+1))).andExpect(status().isPayloadTooLarge());
        }
    }
    @Test void unexpectedErrorsDoNotReturnPrivateDetails() throws Exception {
        AuthService auth = mock(AuthService.class); when(auth.getUserId("valid")).thenReturn(7L);
        mvc(auth).perform(get("/api/failure").header("token", "valid"))
                .andExpect(status().isInternalServerError()).andExpect(content().json(
                        "{\"code\":\"INTERNAL_ERROR\",\"message\":\"An unexpected error occurred\"}"));
    }
    private MockMvc mvc(AuthService auth) {
        return MockMvcBuilders.standaloneSetup(new TestController()).setControllerAdvice(new RestExceptionHandler())
                .addFilters(new ApiAuthenticationFilter(auth, new AuthRateLimitService(new MutableClock()))).build();
    }
    @RestController static class TestController {
        @GetMapping({"/", "/api/private"}) String read() { return "ok"; }
        @PostMapping({"/api/auth/login", "/api/private"}) String write() { return "ok"; }
        @GetMapping("/api/failure") String fail() { throw new IllegalStateException("private connection detail"); }
    }
}
