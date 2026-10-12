package com.is.aigul.controller;

import tools.jackson.databind.ObjectMapper;
import com.is.aigul.dto.RealtimeTokenResponse;
import com.is.aigul.model.LocationNames;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.ProductRepository;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.AiUsageLimitService;
import com.is.aigul.service.CardEditService;
import com.is.aigul.service.FullService;
import com.is.aigul.service.RealtimeTokenService;
import com.is.aigul.service.WhisperService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AudioControllerRealtimeTokenTest {

    @Test
    void authorizedRequestReturnsRealtimeTokenResponse() throws Exception {
        ControllerContext context = new ControllerContext();
        LocationNames location = new LocationNames(1L, 7L, "Osh");
        RealtimeTokenResponse tokenResponse = new RealtimeTokenResponse(
                "single-use-token",
                "scribe_v2_realtime",
                "wss://api.elevenlabs.io/v1/speech-to-text/realtime",
                900,
                List.of("Atlas", "Osh"));

        when(context.authService.getUserId("session-token")).thenReturn(7L);
        when(context.productRepository.findAllNamesByUserId(7L)).thenReturn(List.of("Atlas"));
        when(context.locationNamesRepository.findByUserId(7L)).thenReturn(List.of(location));
        when(context.realtimeTokenService.createToken(List.of("Atlas"), List.of("Osh"))).thenReturn(tokenResponse);

        ResponseEntity<?> response = context.controller.createRealtimeToken("session-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).isEqualTo(tokenResponse);
        assertThat(response.getBody().toString()).doesNotContain("server-secret-key");
    }

    @Test
    void unauthorizedRequestReturns401() throws Exception {
        ControllerContext context = new ControllerContext();
        when(context.authService.getUserId("bad-token")).thenReturn(null);

        ResponseEntity<?> response = context.controller.createRealtimeToken("bad-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(401));
        verifyNoInteractions(context.realtimeTokenService);
    }

    @Test
    void providerFailureReturnsClear5xxWithoutProviderDetails() throws Exception {
        ControllerContext context = new ControllerContext();
        when(context.authService.getUserId("session-token")).thenReturn(7L);
        when(context.productRepository.findAllNamesByUserId(7L)).thenReturn(List.of("Atlas"));
        when(context.locationNamesRepository.findByUserId(7L)).thenReturn(List.of());
        when(context.realtimeTokenService.createToken(List.of("Atlas"), List.of()))
                .thenThrow(new IOException("provider error containing server-secret-key"));

        ResponseEntity<?> response = context.controller.createRealtimeToken("session-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(502));
        assertThat(response.getBody()).isInstanceOf(Map.class);
        assertThat(response.getBody().toString())
                .contains("Unable to create realtime transcription token")
                .doesNotContain("server-secret-key")
                .doesNotContain("provider error");
    }

    private static class ControllerContext {
        private final AudioController controller = new AudioController();
        private final WhisperService whisperService = mock(WhisperService.class);
        private final FullService fullService = mock(FullService.class);
        private final CardEditService cardEditService = mock(CardEditService.class);
        private final RealtimeTokenService realtimeTokenService = mock(RealtimeTokenService.class);
        private final AuthService authService = mock(AuthService.class);
        private final ProductRepository productRepository = mock(ProductRepository.class);
        private final LocationNamesRepository locationNamesRepository = mock(LocationNamesRepository.class);
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final AiUsageLimitService aiUsageLimitService = mock(AiUsageLimitService.class);

        private ControllerContext() {
            ReflectionTestUtils.setField(controller, "whisperService", whisperService);
            ReflectionTestUtils.setField(controller, "fullService", fullService);
            ReflectionTestUtils.setField(controller, "cardEditService", cardEditService);
            ReflectionTestUtils.setField(controller, "realtimeTokenService", realtimeTokenService);
            ReflectionTestUtils.setField(controller, "authService", authService);
            ReflectionTestUtils.setField(controller, "productRepository", productRepository);
            ReflectionTestUtils.setField(controller, "locationNamesRepository", locationNamesRepository);
            ReflectionTestUtils.setField(controller, "objectMapper", objectMapper);
            ReflectionTestUtils.setField(controller, "aiUsageLimitService", aiUsageLimitService);
            when(aiUsageLimitService.reserve(anyLong(), any())).thenReturn(mock(AiUsageLimitService.Permit.class));
        }
    }
}
