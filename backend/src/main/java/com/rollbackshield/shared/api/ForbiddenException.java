package com.rollbackshield.shared.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ForbiddenException extends ApiException {
    public ForbiddenException(String code, String message) {
        super(code, HttpStatus.FORBIDDEN, message, Map.of());
    }
}
