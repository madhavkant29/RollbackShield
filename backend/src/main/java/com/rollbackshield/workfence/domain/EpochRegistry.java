package com.rollbackshield.workfence.domain;

import com.rollbackshield.shared.domain.ReleaseId;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks invalidated (releaseId, epoch) pairs. Invalidation is a single
 * atomic set-add; once invalidated, an epoch can never become valid again
 * (§12). Scoped by release so epoch numbers only need to be unique within a
 * release's own history, not globally.
 */
public final class EpochRegistry {

    private final Set<String> invalidated = ConcurrentHashMap.newKeySet();

    public boolean isValid(ReleaseId releaseId, long epoch) {
        return !invalidated.contains(key(releaseId, epoch));
    }

    public void invalidate(ReleaseId releaseId, long epoch) {
        invalidated.add(key(releaseId, epoch));
    }

    private static String key(ReleaseId releaseId, long epoch) {
        return releaseId + "#" + epoch;
    }
}
