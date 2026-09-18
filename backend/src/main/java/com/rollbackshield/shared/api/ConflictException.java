package com.rollbackshield.shared.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ConflictException extends ApiException {
    public ConflictException(String code, String message, Map<String, String> details) {
        super(code, HttpStatus.CONFLICT, message, details);
    }
}
