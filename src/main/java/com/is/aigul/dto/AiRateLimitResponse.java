package com.is.aigul.dto;

import com.is.aigul.exception.AiRateLimitException.LimitType;

public record AiRateLimitResponse(
        String code,
        String message,
        long retryAfterSeconds,
        LimitType limitType) {
}
