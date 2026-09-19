package com.rollbackshield.integrations.domain.connector;

import java.time.Instant;
import java.util.Objects;

/** One bounded piece of log evidence, with its source cited. */
public record LogEvidence(String logGroup, String stream, Instant timestamp, String message) {

    public LogEvidence {
        Objects.requireNonNull(logGroup, "logGroup");
        Objects.requireNonNull(message, "message");
    }
}
