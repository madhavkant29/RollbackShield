package com.rollbackshield.contract.domain;

import com.rollbackshield.shared.domain.ContractId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.PolicyVersion;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.domain.ServiceId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A versioned rollback contract for a specific release.
 *
 * A contract is immutable per version: changing the rule set produces a new
 * {@link #contentHash()} and a new {@link PolicyVersion}, never a mutation of
 * an existing version. The SDK's PolicyCache keys entirely off
 * (contractId, policyVersion).
 */
public record RollbackContract(
    ContractId contractId,
    OrganizationId organizationId,
    ServiceId serviceId,
    ReleaseId releaseId,
    int contractVersion,
    PolicyVersion policyVersion,
    Instant createdAt,
    Instant activatedAt,
    Duration rollbackWindow,
    List<CompatibilityRule> rules,
    boolean candidateEpochRequiredForAsyncWork,
    ContractStatus status,
    String contentHash
) {

    public RollbackContract {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(rollbackWindow, "rollbackWindow");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(contentHash, "contentHash");
        rules = List.copyOf(rules == null ? List.of() : rules);
    }

    public boolean isActive(Instant now) {
        if (status != ContractStatus.ACTIVE || activatedAt == null) {
            return false;
        }
        return now.isBefore(activatedAt.plus(rollbackWindow));
    }

    public enum ContractStatus {
        DRAFT,
        ACTIVE,
        SUPERSEDED,
        EXPIRED,
        REVOKED
    }
}
