package com.rollbackshield.reversibility.domain;

import java.util.Objects;

public record ReversibilityCheck(String name, boolean passed, ReversibilityBlocker blocker) {

    public ReversibilityCheck {
        Objects.requireNonNull(name, "name");
        if (!passed && blocker == null) {
            throw new IllegalArgumentException("a failed check must carry a blocker explaining why");
        }
        if (passed && blocker != null) {
            throw new IllegalArgumentException("a passed check must not carry a blocker");
        }
    }

    public static ReversibilityCheck pass(String name) {
        return new ReversibilityCheck(name, true, null);
    }

    public static ReversibilityCheck fail(String name, ReversibilityBlocker blocker) {
        return new ReversibilityCheck(name, false, blocker);
    }
}
