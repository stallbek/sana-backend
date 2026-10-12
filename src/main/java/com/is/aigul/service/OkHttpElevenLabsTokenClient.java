package com.is.aigul.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
class OkHttpElevenLabsTokenClient implements ElevenLabsTokenClient {

    private static final String REALTIME_SCRIBE_TOKEN_URL =
            "https://api.elevenlabs.io/v1/single-use-token/realtime_scribe";

    private final OkHttpClient client = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String createRealtimeScribeToken(String apiKey) throws IOException {
        Request request = new Request.Builder()
                .url(REALTIME_SCRIBE_TOKEN_URL)
                .post(RequestBody.create(new byte[0]))
                .addHeader("xi-api-key", apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = ProviderResponseReader.text(response.body(), 64 * 1024);
            if (!response.isSuccessful()) {
                throw new IOException("ElevenLabs realtime token call failed: " + response.code());
            }

            JsonNode root = objectMapper.readTree(body);
            String token = root.path("token").asText("");
            if (token.isBlank()) {
                token = root.path("single_use_token").asText("");
            }
            if (token.isBlank()) {
                throw new IOException("ElevenLabs realtime token response did not include a token");
            }

            return token;
        }
    }
}
