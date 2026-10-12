package com.is.aigul.service;

import java.util.List;

public record TranscriptionResult(
        String text,
        String languageCode,
        double languageProbability,
        List<String> lowConfidenceWords
) {
    public static TranscriptionResult empty() {
        return new TranscriptionResult("", "", 0.0, List.of());
    }
}
