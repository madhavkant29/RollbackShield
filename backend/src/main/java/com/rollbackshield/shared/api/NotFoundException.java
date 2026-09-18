package com.rollbackshield.shared.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class NotFoundException extends ApiException {
    public NotFoundException(String code, String message) {
        super(code, HttpStatus.NOT_FOUND, message, Map.of());
    }
}
