package com.rollbackshield.integrations.adapter;

import com.rollbackshield.integrations.domain.DeploymentObservation;
import com.rollbackshield.integrations.domain.DeploymentObservationRepository;
import com.rollbackshield.shared.domain.DeploymentObservationId;
import com.rollbackshield.shared.domain.ServiceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryDeploymentObservationRepository implements DeploymentObservationRepository {

    private final Map<DeploymentObservationId, DeploymentObservation> store = new ConcurrentHashMap<>();

    @Override
    public DeploymentObservation save(DeploymentObservation observation) {
        store.put(observation.id(), observation);
        return observation;
    }

    @Override
    public Optional<DeploymentObservation> findById(DeploymentObservationId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<DeploymentObservation> findByService(ServiceId serviceId) {
        return store.values().stream()
            .filter(observation -> observation.serviceId().equals(serviceId))
            .sorted(Comparator.comparing(DeploymentObservation::observedAt).reversed())
            .collect(Collectors.toList());
    }

    @Override
    public Optional<DeploymentObservation> findLatestForService(ServiceId serviceId) {
        return findByService(serviceId).stream().findFirst();
    }
}
