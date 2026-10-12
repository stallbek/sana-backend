package com.is.aigul.service;

import com.is.aigul.dto.RealtimeTokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class RealtimeTokenService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeTokenService.class);
    static final String WEBSOCKET_URL = "wss://api.elevenlabs.io/v1/speech-to-text/realtime";
    static final int EXPIRES_IN_SECONDS = 900;
    private static final int REALTIME_KEYTERM_LIMIT = 50;
    private static final int REALTIME_KEYTERM_MAX_CHARS = 20;

    @Value("${elevenlabs.apikey:}")
    private String elevenLabsApiKey;
    @Value("${ai.stt.realtime-model:scribe_v2_realtime}")
    private String realtimeModel;

    private final ElevenLabsTokenClient tokenClient;

    public RealtimeTokenService(ElevenLabsTokenClient tokenClient) {
        this.tokenClient = tokenClient;
    }

    public RealtimeTokenResponse createToken(List<String> knownProductNames, List<String> knownLocationNames)
            throws IOException {
        if (elevenLabsApiKey == null || elevenLabsApiKey.isBlank()) {
            throw new com.is.aigul.exception.ProviderUnavailableException();
        }

        long startedAt = System.nanoTime();
        String token = tokenClient.createRealtimeScribeToken(elevenLabsApiKey);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        log.info("ElevenLabs realtime token created in {} ms", elapsedMs);

        return new RealtimeTokenResponse(
                token,
                realtimeModel,
                WEBSOCKET_URL,
                EXPIRES_IN_SECONDS,
                buildRealtimeKeyterms(knownProductNames, knownLocationNames));
    }

    List<String> buildRealtimeKeyterms(List<String> knownProductNames, List<String> knownLocationNames) {
        Set<String> terms = new LinkedHashSet<>();
        addRealtimeKeyterms(terms, knownProductNames);
        addRealtimeKeyterms(terms, knownLocationNames);
        return terms.stream().limit(REALTIME_KEYTERM_LIMIT).toList();
    }

    private void addRealtimeKeyterms(Set<String> terms, List<String> rawTerms) {
        if (rawTerms == null || rawTerms.isEmpty()) {
            return;
        }

        for (String raw : rawTerms) {
            String term = sanitizeRealtimeKeyterm(raw);
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
    }

    private String sanitizeRealtimeKeyterm(String raw) {
        if (raw == null) {
            return "";
        }

        String term = raw
                .replaceAll("[<>\\{\\}\\[\\]\\\\]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (term.isBlank() || term.length() > REALTIME_KEYTERM_MAX_CHARS) {
            return "";
        }

        return term;
    }
}
