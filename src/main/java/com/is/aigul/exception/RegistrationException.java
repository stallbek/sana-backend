package com.is.aigul.exception;

public class RegistrationException extends RuntimeException {

    public enum Reason {
        INVALID_REGISTRATION,
        IDENTIFIER_ALREADY_REGISTERED
    }

    private final Reason reason;

    public RegistrationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
