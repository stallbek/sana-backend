package com.is.aigul.controller;

import com.is.aigul.config.WalletPassCorsConfiguration;
import com.is.aigul.config.WalletWalletProperties;
import com.is.aigul.service.ClientIpResolver;
import com.is.aigul.service.WalletPassRateLimitService;
import com.is.aigul.service.WalletWalletProxyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class WalletPassControllerTest {

    @Test
    void allowsConfiguredWebsitePreflightAndExposesWalletHeaders() throws Exception {
        MockMvc mvc = standaloneSetup(new WalletPassController(
                mock(WalletWalletProxyService.class),
                mock(WalletPassRateLimitService.class),
                mock(ClientIpResolver.class)))
                .addFilters(new WalletPassCorsConfiguration().apiCorsFilter("https://example.com").getFilter())
                .build();

        mvc.perform(options("/api/passes")
                        .header("Origin", "https://example.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://example.com"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE"))
                .andExpect(header().string("Access-Control-Allow-Headers", "content-type"))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        "Content-Disposition, X-Serial-Number, X-Google-Save-Url, X-Pass-Url, Retry-After"))
                .andExpect(header().string("Access-Control-Max-Age", "3600"));
    }

    @Test
    void rejectsPreflightFromUnapprovedOrigins() throws Exception {
        MockMvc mvc = standaloneSetup(new WalletPassController(
                mock(WalletWalletProxyService.class),
                mock(WalletPassRateLimitService.class),
                mock(ClientIpResolver.class)))
                .addFilters(new WalletPassCorsConfiguration().apiCorsFilter("https://example.com").getFilter())
                .build();

        mvc.perform(options("/api/passes")
                        .header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void walletLimiterRejectsEleventhAttempt() throws Exception {
        WalletWalletProperties properties = new WalletWalletProperties();
        WalletPassRateLimitService limiter = new WalletPassRateLimitService(properties,
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC));
        WalletWalletProxyService proxy = mock(WalletWalletProxyService.class);
        when(proxy.createPass(any(jakarta.servlet.http.HttpServletRequest.class), isNull()))
                .thenReturn(ResponseEntity.ok("{}".getBytes(StandardCharsets.UTF_8)));
        MockMvc mvc = standaloneSetup(new WalletPassController(proxy, limiter, new ClientIpResolver())).build();

        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/passes")
                            .contentType("application/json")
                            .content("{\"attempt\":" + attempt + "}")
                            .header("Authorization", "Bearer client-controlled"))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/passes")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(content().json("{\"error\":\"Too many requests\"}"));
        verify(proxy, times(10))
                .createPass(any(jakarta.servlet.http.HttpServletRequest.class), isNull());
    }

    @Test
    void rateLimitUsesResolvedClientIp() throws Exception {
        WalletPassRateLimitService limiter = mock(WalletPassRateLimitService.class);
        when(limiter.attempt("203.0.113.8"))
                .thenReturn(new WalletPassRateLimitService.Decision(false, 27));
        ClientIpResolver resolver = mock(ClientIpResolver.class);
        when(resolver.resolve(any())).thenReturn("203.0.113.8");
        MockMvc mvc = standaloneSetup(new WalletPassController(
                mock(WalletWalletProxyService.class), limiter, resolver)).build();

        mvc.perform(post("/api/passes")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().is(HttpStatus.TOO_MANY_REQUESTS.value()))
                .andExpect(header().string("Retry-After", "27"));
    }
}
