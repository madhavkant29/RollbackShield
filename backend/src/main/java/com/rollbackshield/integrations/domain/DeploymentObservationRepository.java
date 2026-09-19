package com.rollbackshield.integrations.domain;

import com.rollbackshield.shared.domain.DeploymentObservationId;
import com.rollbackshield.shared.domain.ServiceId;

import java.util.List;
import java.util.Optional;

public interface DeploymentObservationRepository {

    DeploymentObservation save(DeploymentObservation observation);

    Optional<DeploymentObservation> findById(DeploymentObservationId id);

    List<DeploymentObservation> findByService(ServiceId serviceId);

    Optional<DeploymentObservation> findLatestForService(ServiceId serviceId);
}
