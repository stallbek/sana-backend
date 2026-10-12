package com.is.aigul.dto;

public record ProductSuggestionRequest(
        String text,
        int cursor,
        ProductSuggestionContext context,
        int limit
) {
}
