package com.rollbackshield.demo.workfence;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which release epochs are currently valid for performing irreversible
 * side effects. In the real system this lives in the control plane's
 * workfence module behind conditional DynamoDB writes; here it is an
 * in-memory stand-in with the same invariant: once an epoch is invalidated,
 * it can never become valid again, and invalidation is a single atomic
 * operation no concurrent redemption attempt can race past.
 */
public final class EpochRegistry {

    private final Set<Long> invalidatedEpochs = ConcurrentHashMap.newKeySet();

    public boolean isValid(long releaseEpoch) {
        return !invalidatedEpochs.contains(releaseEpoch);
    }

    /** Idempotent: invalidating an already-invalid epoch is a safe no-op. */
    public void invalidate(long releaseEpoch) {
        invalidatedEpochs.add(releaseEpoch);
    }
}
