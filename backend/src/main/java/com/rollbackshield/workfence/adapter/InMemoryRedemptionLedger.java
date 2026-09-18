package com.rollbackshield.workfence.adapter;

import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.workfence.domain.RedeemOutcome;
import com.rollbackshield.workfence.domain.RedemptionLedger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-dev {@link RedemptionLedger}: {@code putIfAbsent} gives the same
 * first-writer-wins guarantee in one process. The `aws` profile uses
 * {@link DynamoDbRedemptionLedger}'s conditional put.
 */
@Component
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryRedemptionLedger implements RedemptionLedger {

    private final ConcurrentHashMap<String, RedeemOutcome> outcomes = new ConcurrentHashMap<>();

    @Override
    public RedeemOutcome commitIfAbsent(String jobId, ReleaseId releaseId, RedeemOutcome candidate) {
        RedeemOutcome existing = outcomes.putIfAbsent(jobId, candidate);
        return existing != null ? existing : candidate;
    }
}
