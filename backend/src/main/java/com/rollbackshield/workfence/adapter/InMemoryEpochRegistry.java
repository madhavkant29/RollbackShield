package com.rollbackshield.workfence.adapter;

import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.workfence.domain.EpochRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-dev {@link EpochRegistry}: an in-process set. Correct for a single
 * backend task only -- the `aws` profile uses {@link DynamoDbEpochRegistry}.
 */
@Component
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryEpochRegistry implements EpochRegistry {

    private final Set<String> invalidated = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isValid(ReleaseId releaseId, long epoch) {
        return !invalidated.contains(key(releaseId, epoch));
    }

    @Override
    public void invalidate(ReleaseId releaseId, long epoch) {
        invalidated.add(key(releaseId, epoch));
    }

    private static String key(ReleaseId releaseId, long epoch) {
        return releaseId + "#" + epoch;
    }
}
