package com.is.aigul.service;

import com.is.aigul.dto.ProductSuggestionContext;
import com.is.aigul.dto.ProductSuggestionRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Component
public class ProductSuggestionRequestValidator {

    private static final int MAX_TEXT_LENGTH = 500;
    private static final int DEFAULT_LIMIT = 5;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 10;

    public ProductSuggestionRequest validate(
            String text,
            String cursorValue,
            String contextValue,
            String limitValue
    ) {
        if (text == null) {
            throw badRequest("text is required");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw badRequest("text must be at most 500 UTF-16 code units");
        }

        int cursor = parseInteger(cursorValue, "cursor is required", "cursor must be an integer");
        if (cursor < 0 || cursor > text.length()) {
            throw badRequest("cursor must be within text");
        }

        ProductSuggestionContext context = parseContext(contextValue);
        if (context != ProductSuggestionContext.COMMAND) {
            throw badRequest("context SEARCH is not supported yet");
        }

        int limit = limitValue == null
                ? DEFAULT_LIMIT
                : parseInteger(limitValue, "limit must be an integer", "limit must be an integer");
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw badRequest("limit must be between 1 and 10");
        }

        return new ProductSuggestionRequest(text, cursor, context, limit);
    }

    private ProductSuggestionContext parseContext(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("context is required");
        }
        try {
            return ProductSuggestionContext.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw badRequest("context must be COMMAND");
        }
    }

    private int parseInteger(String value, String missingMessage, String invalidMessage) {
        if (value == null) {
            throw badRequest(missingMessage);
        }
        if (value.isBlank()) {
            throw badRequest(invalidMessage);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw badRequest(invalidMessage);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }
}
