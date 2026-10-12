package com.is.aigul.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class WhisperService {

    private static final Logger log = LoggerFactory.getLogger(WhisperService.class);
    private static final String ELEVENLABS_STT_URL = "https://api.elevenlabs.io/v1/speech-to-text";
    private static final double LOW_CONFIDENCE_LOGPROB_THRESHOLD = -1.0;

    @Value("${elevenlabs.apikey:}")
    private String elevenLabsApiKey;
    @Value("${ai.stt.model:scribe_v2}")
    private String sttModel;
    @Value("${ai.stt.language-code:}")
    private String languageCode;
    @Value("${ai.stt.max-keyterms:100}")
    private int maxKeyterms;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();


    public String audioToText(File audioFile, List<String> knownProductNames) throws IOException {
        return transcribe(audioFile, knownProductNames, null).text();
    }

    public String audioToText(File audioFile, List<String> knownProductNames, List<String> knownLocationNames) throws IOException {
        return transcribe(audioFile, knownProductNames, knownLocationNames).text();
    }

    public TranscriptionResult transcribe(File audioFile, List<String> knownProductNames, List<String> knownLocationNames) throws IOException {
        if (elevenLabsApiKey == null || elevenLabsApiKey.isBlank()) {
            throw new com.is.aigul.exception.ProviderUnavailableException();
        }

        RequestBody requestBody = buildScribeRequestBody(audioFile, knownProductNames, knownLocationNames);

        Request request = new Request.Builder()
                .url(ELEVENLABS_STT_URL)
                .post(requestBody)
                .addHeader("xi-api-key", elevenLabsApiKey)
                .build();

        long startedAt = System.nanoTime();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = ProviderResponseReader.text(response.body(), 10 * 1024 * 1024);

            if (!response.isSuccessful()) {
                throw new IOException("ElevenLabs STT call failed: " + response.code());
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("STT completed in {} ms", elapsedMs);
            return parseTranscriptionResult(responseBody);
        }
    }

    MultipartBody buildScribeRequestBody(File audioFile, List<String> knownProductNames, List<String> knownLocationNames) throws IOException {
        MediaType audioType = detectAudioType(audioFile);
        RequestBody fileBody = RequestBody.create(audioFile, audioType);

        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .addFormDataPart("model_id", sttModel)
                .addFormDataPart("tag_audio_events", "false")
                .addFormDataPart("diarize", "false")
                .addFormDataPart("timestamps_granularity", "word")
                .addFormDataPart("file_format", "other")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("no_verbatim", "false");

        if (languageCode != null && !languageCode.isBlank()) {
            bodyBuilder.addFormDataPart("language_code", languageCode.trim());
        }

        for (String keyterm : buildKeyterms(knownProductNames, knownLocationNames)) {
            bodyBuilder.addFormDataPart("keyterms", keyterm);
        }

        return bodyBuilder.build();
    }

    public String audioToText(File audioFile) throws IOException {
        return audioToText(audioFile, null);
    }

    TranscriptionResult parseTranscriptionResult(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        String text = root.path("text").asText("");
        String detectedLanguage = root.path("language_code").asText("");
        double languageProbability = root.path("language_probability").asDouble(0.0);
        List<String> lowConfidenceWords = new ArrayList<>();

        JsonNode words = root.path("words");
        if (words.isArray()) {
            for (JsonNode word : words) {
                if ("word".equals(word.path("type").asText()) &&
                        word.path("logprob").asDouble(0.0) < LOW_CONFIDENCE_LOGPROB_THRESHOLD) {
                    String value = word.path("text").asText("");
                    if (!value.isBlank()) {
                        lowConfidenceWords.add(value);
                    }
                }
            }
        }

        return new TranscriptionResult(text, detectedLanguage, languageProbability, List.copyOf(lowConfidenceWords));
    }

    List<String> buildKeyterms(List<String> knownProductNames, List<String> knownLocationNames) {
        Set<String> terms = new LinkedHashSet<>();
        addKeyterms(terms, knownProductNames);
        addKeyterms(terms, knownLocationNames);

        int limit = Math.max(0, Math.min(maxKeyterms, 100));
        return terms.stream().limit(limit).toList();
    }

    private void addKeyterms(Set<String> terms, List<String> rawTerms) {
        if (rawTerms == null || rawTerms.isEmpty()) {
            return;
        }

        for (String raw : rawTerms) {
            String term = sanitizeKeyterm(raw);
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
    }

    private String sanitizeKeyterm(String raw) {
        if (raw == null) {
            return "";
        }

        String term = raw
                .replaceAll("[<>\\{\\}\\[\\]\\\\]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (term.length() >= 50 || term.split("\\s+").length > 5) {
            return "";
        }

        return term;
    }

    private MediaType detectAudioType(File file) throws IOException {
        String mime = Files.probeContentType(file.toPath());

        if (mime == null) {
            // fallback by extension
            String name = file.getName().toLowerCase();
            if (name.endsWith(".wav")) mime = "audio/wav";
            else if (name.endsWith(".mp3")) mime = "audio/mpeg";
            else if (name.endsWith(".m4a")) mime = "audio/mp4";
            else if (name.endsWith(".webm")) mime = "audio/webm";
            else if (name.endsWith(".ogg")) mime = "audio/ogg";
            else throw new IllegalArgumentException("Unsupported audio format");
        }

        return MediaType.parse(mime);
    }
}
