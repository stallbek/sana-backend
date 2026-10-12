package com.is.aigul.dto;

public record AuthErrorResponse(
        String code,
        String message
) {
}
