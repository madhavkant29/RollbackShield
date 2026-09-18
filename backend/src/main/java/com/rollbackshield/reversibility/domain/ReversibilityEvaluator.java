package com.rollbackshield.reversibility.domain;

import com.rollbackshield.release.domain.ReleaseState;

import java.util.List;

public final class ReversibilityEvaluator {

    private ReversibilityEvaluator() {
    }

    public static ReversibilityReport evaluate(ReleaseState releaseState, List<ReversibilityCheck> checks) {
        if (releaseState == ReleaseState.COMMITTED) {
            return new ReversibilityReport(ReversibilityStatus.COMMITTED, checks);
        }

        if (checks == null || checks.isEmpty()) {
            return new ReversibilityReport(ReversibilityStatus.UNKNOWN, List.of());
        }

        boolean allPassed = checks.stream().allMatch(ReversibilityCheck::passed);
        ReversibilityStatus status = allPassed ? ReversibilityStatus.REVERSIBLE : ReversibilityStatus.AT_RISK;
        return new ReversibilityReport(status, checks);
    }
}
