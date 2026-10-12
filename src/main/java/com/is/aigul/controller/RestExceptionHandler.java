package com.is.aigul.controller;

import com.is.aigul.dto.AiRateLimitResponse;
import com.is.aigul.dto.AuthErrorResponse;
import com.is.aigul.exception.AiRateLimitException;
import com.is.aigul.exception.RegistrationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(AiRateLimitException.class)
    public ResponseEntity<AiRateLimitResponse> handleAiRateLimit(AiRateLimitException exception) {
        AiRateLimitResponse body = new AiRateLimitResponse(
                "AI_RATE_LIMITED",
                exception.getMessage(),
                exception.getRetryAfterSeconds(),
                exception.getLimitType());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<AuthErrorResponse> handleRegistration(
            RegistrationException exception
    ) {
        HttpStatus status = exception.getReason()
                == RegistrationException.Reason.IDENTIFIER_ALREADY_REGISTERED
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new AuthErrorResponse(
                exception.getReason().name(),
                exception.getMessage()
        ));
    }

    @ExceptionHandler(com.is.aigul.exception.UnsupportedCommandException.class)
    public ResponseEntity<AuthErrorResponse> handleUnsupported() {
        return ResponseEntity.badRequest().body(new AuthErrorResponse(
                "UNSUPPORTED_OPERATION", "This operation is not supported in the showcase; use the structured APIs"));
    }

    @ExceptionHandler(com.is.aigul.exception.ProviderUnavailableException.class)
    public ResponseEntity<AuthErrorResponse> handleUnconfiguredProvider() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new AuthErrorResponse("SERVICE_UNAVAILABLE", "The requested integration is not configured"));
    }

    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    public ResponseEntity<AuthErrorResponse> handleInvalidRequest() {
        return ResponseEntity.badRequest().body(new AuthErrorResponse("INVALID_REQUEST", "Invalid request"));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<AuthErrorResponse> handleStatus(org.springframework.web.server.ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new AuthErrorResponse(
                "REQUEST_FAILED", "Request could not be completed"));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<AuthErrorResponse> handleMalformedRequest() {
        return handleInvalidRequest();
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<AuthErrorResponse> handleLargeUpload() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new AuthErrorResponse("PAYLOAD_TOO_LARGE", "Upload exceeds the configured limit"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AuthErrorResponse> handleUnexpectedFailure() {
        // Avoid logging exception messages or stack traces containing client/provider data.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AuthErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
