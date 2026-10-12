package com.is.aigul.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "walletwallet")
public class WalletWalletProperties {

    private String baseUrl = "https://api.walletwallet.dev";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);
    private long maxRequestBytes = 2L * 1024 * 1024;
    private int attemptsPerHour = 10;

    @PostConstruct
    void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("walletwallet.base-url must not be blank");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalStateException("walletwallet.connect-timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalStateException("walletwallet.read-timeout must be positive");
        }
        if (maxRequestBytes <= 0 || maxRequestBytes >= Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "walletwallet.max-request-bytes must be positive and less than " + Integer.MAX_VALUE);
        }
        if (attemptsPerHour <= 0) {
            throw new IllegalStateException("walletwallet.attempts-per-hour must be positive");
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public long getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public int getAttemptsPerHour() {
        return attemptsPerHour;
    }

    public void setAttemptsPerHour(int attemptsPerHour) {
        this.attemptsPerHour = attemptsPerHour;
    }
}
