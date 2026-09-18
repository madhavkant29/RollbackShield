package com.rollbackshield.reversibility.domain;

import java.util.Objects;

public record ReversibilityBlocker(String code, String description) {

    public ReversibilityBlocker {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(description, "description");
    }

    public static final String POLICY_EXPIRED = "POLICY_EXPIRED";
    public static final String INCOMPATIBLE_STATE_WRITTEN = "INCOMPATIBLE_STATE_WRITTEN";
    public static final String UNFENCED_WORK_PENDING = "UNFENCED_WORK_PENDING";
    public static final String NO_ACTIVE_CONTRACT = "NO_ACTIVE_CONTRACT";
}
