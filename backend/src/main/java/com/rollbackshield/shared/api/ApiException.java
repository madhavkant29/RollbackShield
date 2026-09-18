package com.rollbackshield.shared.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Base class for exceptions that carry a stable error code and HTTP status.
 * Application-layer services throw subclasses of this; controllers never
 * construct ApiError bodies by hand.
 */
public abstract class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, String> details;

    protected ApiException(String code, HttpStatus status, String message, Map<String, String> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details == null ? Map.of() : details;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public Map<String, String> details() { return details; }
}
