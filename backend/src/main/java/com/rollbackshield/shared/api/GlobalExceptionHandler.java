package com.rollbackshield.shared.api;

import com.rollbackshield.release.domain.ReleaseTransitionException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Translates domain/application exceptions into the structured ApiError body
 * (§53). Frontend code parses `code`, never `message` strings.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        String requestId = requestId();
        return ResponseEntity.status(ex.status())
            .body(ApiError.of(ex.code(), ex.getMessage(), requestId, ex.details()));
    }

    /**
     * ReleaseTransitionException is a pure domain class with no HTTP
     * dependency (§18) -- this is where that domain invariant becomes an
     * HTTP-shaped, stably-coded response, not inside release/domain itself.
     */
    @ExceptionHandler(ReleaseTransitionException.class)
    public ResponseEntity<ApiError> handleReleaseTransition(ReleaseTransitionException ex) {
        Map<String, String> details = Map.of("from", ex.from().name(), "to", ex.to().name());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of(ReleaseTransitionException.ERROR_CODE, ex.getMessage(), requestId(), details));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
            details.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError.of("VALIDATION_FAILED", "Request failed validation", requestId(), details));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred", requestId(), Map.of()));
    }

    private String requestId() {
        String existing = MDC.get("requestId");
        return existing != null ? existing : UUID.randomUUID().toString();
    }
}
