package com.rollbackshield.reversibility.domain;

import java.util.List;
import java.util.Objects;

public record ReversibilityReport(ReversibilityStatus status, List<ReversibilityCheck> checks) {

    public ReversibilityReport {
        Objects.requireNonNull(status, "status");
        checks = List.copyOf(checks == null ? List.of() : checks);
    }

    public List<ReversibilityBlocker> blockers() {
        return checks.stream()
            .filter(c -> !c.passed())
            .map(ReversibilityCheck::blocker)
            .toList();
    }
}
