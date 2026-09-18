package com.rollbackshield.workfence.domain;

import com.rollbackshield.shared.domain.ReleaseId;

/**
 * Port for the per-job redemption ledger. The first committed outcome for a
 * jobId is final: every later call, no matter how many or what candidate it
 * proposes, observes that same outcome. This is what makes an irreversible
 * side effect safe under at-least-once delivery (ADR-005).
 *
 * In-memory for local dev; DynamoDB-backed under the `aws` profile where the
 * commit is a conditional put, so two backend tasks cannot both decide.
 */
public interface RedemptionLedger {

    /**
     * Atomically records {@code candidate} for {@code jobId} if no outcome
     * exists yet, and returns the effective committed outcome either way.
     */
    RedeemOutcome commitIfAbsent(String jobId, ReleaseId releaseId, RedeemOutcome candidate);
}
