package com.is.aigul.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import okhttp3.ResponseBody;

/** Bounds upstream bodies before buffering them; Content-Length alone is not trustworthy. */
public final class ProviderResponseReader {
    private ProviderResponseReader() { }
    public static byte[] bytes(ResponseBody body, int maxBytes) throws IOException {
        if (body == null) return new byte[0];
        if (body.contentLength() > maxBytes) throw new IOException("Provider response exceeds configured limit");
        byte[] bytes = body.byteStream().readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new IOException("Provider response exceeds configured limit");
        return bytes;
    }
    public static String text(ResponseBody body, int maxBytes) throws IOException {
        return new String(bytes(body, maxBytes), StandardCharsets.UTF_8);
    }
}
