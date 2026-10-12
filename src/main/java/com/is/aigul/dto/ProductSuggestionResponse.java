package com.is.aigul.dto;

import java.util.List;

public record ProductSuggestionResponse(
        int replacementStart,
        int replacementEnd,
        String commonCompletion,
        List<ProductSuggestionItem> suggestions
) {
    public static ProductSuggestionResponse empty(int cursor) {
        return new ProductSuggestionResponse(cursor, cursor, null, List.of());
    }
}
