package com.rollbackshield.release.domain;

import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.domain.ServiceId;

import java.time.Instant;
import java.util.Objects;

public record Release(
    ReleaseId id,
    OrganizationId organizationId,
    ServiceId serviceId,
    String previousVersionLabel,
    String candidateVersionLabel,
    ReleaseState state,
    long epoch,
    Instant createdAt,
    Instant updatedAt
) {

    public Release {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Release draft(OrganizationId organizationId, ServiceId serviceId,
                                 String previousVersionLabel, String candidateVersionLabel) {
        Instant now = Instant.now();
        return new Release(ReleaseId.newId(), organizationId, serviceId, previousVersionLabel,
            candidateVersionLabel, ReleaseState.DRAFT, 0L, now, now);
    }

    /** Central transition point -- every state change in the system goes through this (§5). */
    public Release transitionTo(ReleaseState target) {
        if (!state.canTransitionTo(target)) {
            throw new ReleaseTransitionException(state, target);
        }
        long nextEpoch = (target == ReleaseState.PROTECTED_ROLLOUT) ? epoch + 1 : epoch;
        return new Release(id, organizationId, serviceId, previousVersionLabel, candidateVersionLabel,
            target, nextEpoch, createdAt, Instant.now());
    }
}
