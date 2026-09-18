package com.rollbackshield.contract.adapter;

import com.rollbackshield.contract.domain.RollbackContract;
import com.rollbackshield.contract.domain.RollbackContractRepository;
import com.rollbackshield.shared.domain.ContractId;
import com.rollbackshield.shared.domain.ReleaseId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryRollbackContractRepository implements RollbackContractRepository {

    private final Map<ContractId, RollbackContract> store = new ConcurrentHashMap<>();

    @Override
    public RollbackContract save(RollbackContract contract) {
        store.put(contract.contractId(), contract);
        return contract;
    }

    @Override
    public Optional<RollbackContract> findById(ContractId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<RollbackContract> findActiveForRelease(ReleaseId releaseId) {
        return store.values().stream()
            .filter(c -> c.releaseId().equals(releaseId) && c.status() == RollbackContract.ContractStatus.ACTIVE)
            .findFirst();
    }
}
