package com.rollbackshield.reversibility.domain;

import com.rollbackshield.release.domain.ReleaseState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReversibilityEvaluatorTest {

    @Test
    void reversibleWhenAllChecksPassAndReleaseNotCommitted() {
        var report = ReversibilityEvaluator.evaluate(ReleaseState.PROTECTED_ROLLOUT, List.of(
            ReversibilityCheck.pass("Data compatibility"),
            ReversibilityCheck.pass("Queued work"),
            ReversibilityCheck.pass("Policy freshness")
        ));

        assertThat(report.status()).isEqualTo(ReversibilityStatus.REVERSIBLE);
        assertThat(report.blockers()).isEmpty();
    }

    @Test
    void atRiskWhenAnyCheckFails() {
        var blocker = new ReversibilityBlocker(ReversibilityBlocker.POLICY_EXPIRED, "policy is stale");
        var report = ReversibilityEvaluator.evaluate(ReleaseState.PROTECTED_ROLLOUT, List.of(
            ReversibilityCheck.pass("Data compatibility"),
            ReversibilityCheck.fail("Policy freshness", blocker)
        ));

        assertThat(report.status()).isEqualTo(ReversibilityStatus.AT_RISK);
        assertThat(report.blockers()).containsExactly(blocker);
    }

    @Test
    void committedStatusOverridesChecksOnceReleaseIsCommitted() {
        var report = ReversibilityEvaluator.evaluate(ReleaseState.COMMITTED, List.of(
            ReversibilityCheck.fail("Data compatibility",
                new ReversibilityBlocker(ReversibilityBlocker.INCOMPATIBLE_STATE_WRITTEN, "irrelevant now"))
        ));

        // A committed release is no longer expected to roll back; the rollback
        // window is closed by definition, so status is COMMITTED regardless
        // of what the (now moot) compatibility checks say.
        assertThat(report.status()).isEqualTo(ReversibilityStatus.COMMITTED);
    }

    @Test
    void unknownWhenNoChecksHaveBeenEvaluatedYet() {
        var report = ReversibilityEvaluator.evaluate(ReleaseState.PREPARING, List.of());

        assertThat(report.status()).isEqualTo(ReversibilityStatus.UNKNOWN);
    }
}
