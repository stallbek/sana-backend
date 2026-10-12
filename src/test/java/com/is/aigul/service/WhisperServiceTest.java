package com.is.aigul.service;

import okhttp3.MultipartBody;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WhisperServiceTest {

    @Test
    void keytermsAreSanitizedDeduplicatedAndCappedAtConfiguredLimit() {
        WhisperService service = new WhisperService();
        ReflectionTestUtils.setField(service, "maxKeyterms", 3);

        List<String> terms = service.buildKeyterms(
                List.of("Atlas", "Atlas", "bad <term>", "too many words in this product name"),
                List.of("Osh", "This term is deliberately longer than fifty characters total")
        );

        assertThat(terms).containsExactly("Atlas", "bad term", "Osh");
    }

    @Test
    void buildScribeRequestUsesScribeModelAndKeyterms() throws Exception {
        WhisperService service = new WhisperService();
        ReflectionTestUtils.setField(service, "sttModel", "scribe_v2");
        ReflectionTestUtils.setField(service, "languageCode", "");
        ReflectionTestUtils.setField(service, "maxKeyterms", 100);

        File audio = Files.createTempFile("aigul-test", ".wav").toFile();
        try {
            MultipartBody body = service.buildScribeRequestBody(audio, List.of("Atlas"), List.of("Osh"));

            List<String> formParts = new ArrayList<>();
            for (MultipartBody.Part part : body.parts()) {
                if (part.headers() != null) {
                    formParts.add(part.headers().toString());
                }
            }

            assertThat(formParts).anySatisfy(header -> assertThat(header).contains("name=\"model_id\""));
            assertThat(formParts).anySatisfy(header -> assertThat(header).contains("name=\"keyterms\""));
        } finally {
            Files.deleteIfExists(audio.toPath());
        }
    }

    @Test
    void parseTranscriptionResultExtractsTextLanguageAndLowConfidenceWords() throws Exception {
        WhisperService service = new WhisperService();

        TranscriptionResult result = service.parseTranscriptionResult("""
                {
                  "language_code": "ky",
                  "language_probability": 0.82,
                  "text": "сат 5 атлас",
                  "words": [
                    {"text": "сат", "type": "word", "logprob": -0.2},
                    {"text": "атлас", "type": "word", "logprob": -1.4}
                  ]
                }
                """);

        assertThat(result.text()).isEqualTo("сат 5 атлас");
        assertThat(result.languageCode()).isEqualTo("ky");
        assertThat(result.languageProbability()).isEqualTo(0.82);
        assertThat(result.lowConfidenceWords()).containsExactly("атлас");
    }
}
