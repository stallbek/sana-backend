package com.is.aigul.dto;

import java.util.List;

public record RealtimeTokenResponse(
        String token,
        String modelId,
        String websocketUrl,
        int expiresInSeconds,
        List<String> keyterms) {
}
