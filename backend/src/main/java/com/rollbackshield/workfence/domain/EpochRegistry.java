package com.rollbackshield.workfence.domain;

import com.rollbackshield.shared.domain.ReleaseId;

/**
 * Port tracking invalidated (releaseId, epoch) pairs. Invalidation is a
 * single atomic set-add; once invalidated, an epoch can never become valid
 * again (§12). Scoped by release so epoch numbers only need to be unique
 * within a release's own history, not globally.
 *
 * In-memory for local dev; DynamoDB-backed under the `aws` profile so the
 * fence survives restarts and is shared across backend tasks (ADR-005).
 */
public interface EpochRegistry {

    boolean isValid(ReleaseId releaseId, long epoch);

    void invalidate(ReleaseId releaseId, long epoch);
}
