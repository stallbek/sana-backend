package com.is.aigul.exception;

public class AiRateLimitException extends RuntimeException {

    public enum LimitType {
        BURST,
        HOURLY,
        REALTIME_TOKENS,
        CONCURRENT
    }

    private final long retryAfterSeconds;
    private final LimitType limitType;

    public AiRateLimitException(long retryAfterSeconds, LimitType limitType) {
        super("Too many AI requests. Please wait before trying again.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.limitType = limitType;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
    public LimitType getLimitType() { return limitType; }
}
