package com.is.aigul.service;

import java.io.IOException;

interface ElevenLabsTokenClient {
    String createRealtimeScribeToken(String apiKey) throws IOException;
}
