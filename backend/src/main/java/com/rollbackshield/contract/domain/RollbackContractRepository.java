package com.rollbackshield.contract.domain;

import com.rollbackshield.shared.domain.ContractId;
import com.rollbackshield.shared.domain.ReleaseId;

import java.util.Optional;

public interface RollbackContractRepository {
    RollbackContract save(RollbackContract contract);
    Optional<RollbackContract> findById(ContractId id);
    Optional<RollbackContract> findActiveForRelease(ReleaseId releaseId);
}
