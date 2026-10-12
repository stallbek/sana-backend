package com.is.aigul.exception;

public class ProviderUnavailableException extends RuntimeException {
    public ProviderUnavailableException() {
        super("The requested integration is not configured");
    }
}
