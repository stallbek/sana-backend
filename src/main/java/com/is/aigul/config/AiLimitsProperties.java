package com.is.aigul.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.limits")
public class AiLimitsProperties {

    public enum Mode {
        DISABLED,
        OBSERVE,
        ENFORCE
    }

    private Mode mode = Mode.ENFORCE;
    private int primarySubmissionsPerMinute = 10;
    private int operationsPerMinute = 20;
    private int unitsPerMinute = 50;
    private int unitsPerHour = 500;
    private int realtimeTokensPerHour = 100;
    private int concurrentOperations = 2;
    private int concurrentRetrySeconds = 1;
    private int textMaxCharacters = 2_000;
    private long uploadMaxBytes = 10L * 1024 * 1024;
    private int cleanupAfterMinutes = 65;

    @PostConstruct
    void validate() {
        requirePositive(primarySubmissionsPerMinute, "primary-submissions-per-minute");
        requirePositive(operationsPerMinute, "operations-per-minute");
        requirePositive(unitsPerMinute, "units-per-minute");
        requirePositive(unitsPerHour, "units-per-hour");
        requirePositive(realtimeTokensPerHour, "realtime-tokens-per-hour");
        requirePositive(concurrentOperations, "concurrent-operations");
        requirePositive(concurrentRetrySeconds, "concurrent-retry-seconds");
        requirePositive(textMaxCharacters, "text-max-characters");
        if (uploadMaxBytes <= 0) {
            throw new IllegalStateException("ai.limits.upload-max-bytes must be positive");
        }
        requirePositive(cleanupAfterMinutes, "cleanup-after-minutes");
    }

    private void requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalStateException("ai.limits." + property + " must be positive");
        }
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public int getPrimarySubmissionsPerMinute() { return primarySubmissionsPerMinute; }
    public void setPrimarySubmissionsPerMinute(int value) { this.primarySubmissionsPerMinute = value; }
    public int getOperationsPerMinute() { return operationsPerMinute; }
    public void setOperationsPerMinute(int value) { this.operationsPerMinute = value; }
    public int getUnitsPerMinute() { return unitsPerMinute; }
    public void setUnitsPerMinute(int value) { this.unitsPerMinute = value; }
    public int getUnitsPerHour() { return unitsPerHour; }
    public void setUnitsPerHour(int value) { this.unitsPerHour = value; }
    public int getRealtimeTokensPerHour() { return realtimeTokensPerHour; }
    public void setRealtimeTokensPerHour(int value) { this.realtimeTokensPerHour = value; }
    public int getConcurrentOperations() { return concurrentOperations; }
    public void setConcurrentOperations(int value) { this.concurrentOperations = value; }
    public int getConcurrentRetrySeconds() { return concurrentRetrySeconds; }
    public void setConcurrentRetrySeconds(int value) { this.concurrentRetrySeconds = value; }
    public int getTextMaxCharacters() { return textMaxCharacters; }
    public void setTextMaxCharacters(int value) { this.textMaxCharacters = value; }
    public long getUploadMaxBytes() { return uploadMaxBytes; }
    public void setUploadMaxBytes(long value) { this.uploadMaxBytes = value; }
    public int getCleanupAfterMinutes() { return cleanupAfterMinutes; }
    public void setCleanupAfterMinutes(int value) { this.cleanupAfterMinutes = value; }
}
