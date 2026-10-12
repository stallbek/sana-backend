package com.is.aigul.service;

import com.is.aigul.dto.RealtimeTokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealtimeTokenServiceTest {

    @Test
    void createTokenReturnsRealtimeConnectionDetailsWithoutLeakingApiKey() throws Exception {
        RealtimeTokenService service = new RealtimeTokenService(apiKey -> "single-use-token");
        ReflectionTestUtils.setField(service, "elevenLabsApiKey", "server-secret-key");
        ReflectionTestUtils.setField(service, "realtimeModel", "scribe_v2_realtime");

        RealtimeTokenResponse response = service.createToken(List.of("Atlas"), List.of("Osh"));

        assertThat(response.token()).isEqualTo("single-use-token");
        assertThat(response.modelId()).isEqualTo("scribe_v2_realtime");
        assertThat(response.websocketUrl()).isEqualTo(RealtimeTokenService.WEBSOCKET_URL);
        assertThat(response.expiresInSeconds()).isEqualTo(900);
        assertThat(response.keyterms()).containsExactly("Atlas", "Osh");
        assertThat(response.toString()).doesNotContain("server-secret-key");
    }

    @Test
    void createTokenFailsWhenApiKeyIsMissing() {
        RealtimeTokenService service = new RealtimeTokenService(apiKey -> "single-use-token");
        ReflectionTestUtils.setField(service, "elevenLabsApiKey", "");

        assertThatThrownBy(() -> service.createToken(List.of("Atlas"), List.of("Osh")))
                .isInstanceOf(com.is.aigul.exception.ProviderUnavailableException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void realtimeKeytermsAreSanitizedDeduplicatedAndCappedForRealtimeLimits() {
        RealtimeTokenService service = new RealtimeTokenService(apiKey -> "single-use-token");

        List<String> products = new ArrayList<>();
        products.add("Atlas");
        products.add("Atlas");
        products.add("bad <term>");
        products.add("This term is too long");
        for (int i = 0; i < 60; i++) {
            products.add("P" + i);
        }

        List<String> terms = service.buildRealtimeKeyterms(products, List.of("Osh"));

        assertThat(terms).hasSize(50);
        assertThat(terms).startsWith("Atlas", "bad term", "P0");
        assertThat(terms).doesNotContain("This term is too long");
        assertThat(terms).allSatisfy(term -> assertThat(term.length()).isLessThanOrEqualTo(20));
    }
}
