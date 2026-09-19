package com.rollbackshield.shared.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Request was well-formed but semantically invalid (400). */
public class ValidationException extends ApiException {
    public ValidationException(String code, String message, Map<String, String> details) {
        super(code, HttpStatus.BAD_REQUEST, message, details);
    }
}
