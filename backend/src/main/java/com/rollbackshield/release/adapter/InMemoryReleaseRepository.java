package com.rollbackshield.release.adapter;

import com.rollbackshield.release.domain.Release;
import com.rollbackshield.release.domain.ReleaseRepository;
import com.rollbackshield.release.domain.ReleaseState;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.domain.ServiceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryReleaseRepository implements ReleaseRepository {

    private final Map<ReleaseId, Release> store = new ConcurrentHashMap<>();

    @Override
    public Release save(Release release) {
        store.put(release.id(), release);
        return release;
    }

    @Override
    public Optional<Release> findById(ReleaseId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Release> findByService(ServiceId serviceId) {
        return store.values().stream()
            .filter(r -> r.serviceId().equals(serviceId))
            .collect(Collectors.toList());
    }

    @Override
    public boolean compareAndSave(Release release, ReleaseState expectedState) {
        ReleaseId id = release.id();
        // computeIfPresent gives us an atomic check-then-set on this key.
        Release[] result = new Release[1];
        store.computeIfPresent(id, (key, current) -> {
            if (current.state() == expectedState) {
                result[0] = release;
                return release;
            }
            return current; // unchanged: caller's expected-state precondition failed
        });
        return result[0] != null;
    }
}
