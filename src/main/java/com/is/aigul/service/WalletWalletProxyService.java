package com.is.aigul.service;

import com.is.aigul.config.WalletWalletProperties;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class WalletWalletProxyService {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final Set<String> RESPONSE_HEADERS = Set.of(
            HttpHeaders.CONTENT_TYPE.toLowerCase(),
            HttpHeaders.CONTENT_DISPOSITION.toLowerCase(),
            "x-serial-number",
            "x-google-save-url",
            "x-pass-url",
            HttpHeaders.RETRY_AFTER.toLowerCase());

    private final WalletWalletProperties properties;
    private final OkHttpClient httpClient;

    public WalletWalletProxyService(WalletWalletProperties properties) {
        this.properties = properties;
        this.httpClient = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    public ResponseEntity<byte[]> createPass(HttpServletRequest request, String format) {
        if (request.getContentLengthLong() > properties.getMaxRequestBytes()) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds configured limit");
        }
        try {
            int readLimit = Math.toIntExact(properties.getMaxRequestBytes() + 1);
            byte[] requestBody = request.getInputStream().readNBytes(readLimit);
            return createPass(requestBody, format);
        } catch (IOException exception) {
            return error(HttpStatus.BAD_REQUEST, "Invalid request body");
        }
    }

    public ResponseEntity<byte[]> createPass(byte[] requestBody, String format) {
        if (requestBody.length > properties.getMaxRequestBytes()) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds configured limit");
        }
        if (format != null && !format.equals("json") && !format.equals("binary")) {
            return error(HttpStatus.BAD_REQUEST, "Unsupported format");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "Wallet pass service is not configured");
        }

        HttpUrl baseUrl = HttpUrl.parse(properties.getBaseUrl());
        if (baseUrl == null) {
            return error(HttpStatus.BAD_GATEWAY, "Wallet pass provider unavailable");
        }
        HttpUrl.Builder url = baseUrl.newBuilder()
                .addPathSegment("api")
                .addPathSegment("passes");
        if (format != null) {
            url.addQueryParameter("format", format);
        }

        Request request = new Request.Builder()
                .url(url.build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .post(RequestBody.create(requestBody, JSON))
                .build();

        try (Response upstream = httpClient.newCall(request).execute()) {
            if (!upstream.isSuccessful()) {
                return error(upstream.code() == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY,
                        "Wallet pass provider unavailable");
            }
            ResponseBody body = upstream.body();
            byte[] responseBytes = ProviderResponseReader.bytes(body, 10 * 1024 * 1024);
            HttpHeaders headers = approvedHeaders(upstream);
            return new ResponseEntity<>(responseBytes, headers, HttpStatus.valueOf(upstream.code()));
        } catch (IOException exception) {
            return error(HttpStatus.BAD_GATEWAY, "Wallet pass provider unavailable");
        }
    }

    private HttpHeaders approvedHeaders(Response response) {
        HttpHeaders result = new HttpHeaders();
        response.headers().names().stream()
                .filter(name -> RESPONSE_HEADERS.contains(name.toLowerCase()))
                .forEach(name -> {
                    List<String> values = response.headers(name);
                    values.forEach(value -> result.add(name, value));
                });
        return result;
    }

    public static ResponseEntity<byte[]> error(HttpStatus status, String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        byte[] body = ("{\"error\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body);
    }
}
