package com.rollbackshield.sdk;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An immutable, fully-indexed snapshot of one rollback contract's rules.
 *
 * Indexing happens once, at fetch/deserialize time — never per mutation.
 * This is what makes the hot-path evaluation in {@link ContractEvaluator} a
 * plain map lookup instead of a linear scan (§10: "precompiled rule
 * representation where sensible").
 */
public final class PolicySnapshot {

    private final String contractId;
    private final int contractVersion;
    private final long policyVersion;
    private final boolean candidateEpochRequiredForAsyncWork;
    private final Instant fetchedAt;
    private final Map<String, List<SdkCompatibilityRule>> rulesByEntityField;

    public PolicySnapshot(String contractId, int contractVersion, long policyVersion,
                           boolean candidateEpochRequiredForAsyncWork, Instant fetchedAt,
                           List<SdkCompatibilityRule> rules) {
        this.contractId = contractId;
        this.contractVersion = contractVersion;
        this.policyVersion = policyVersion;
        this.candidateEpochRequiredForAsyncWork = candidateEpochRequiredForAsyncWork;
        this.fetchedAt = fetchedAt;
        this.rulesByEntityField = rules.stream()
            .collect(Collectors.groupingBy(r -> key(r.entity(), r.field())));
    }

    private static String key(String entity, String field) {
        return entity + "\u0000" + field;
    }

    public List<SdkCompatibilityRule> rulesFor(String entity, String field) {
        return rulesByEntityField.getOrDefault(key(entity, field), List.of());
    }

    public String contractId() {
        return contractId;
    }

    public int contractVersion() {
        return contractVersion;
    }

    public long policyVersion() {
        return policyVersion;
    }

    public boolean candidateEpochRequiredForAsyncWork() {
        return candidateEpochRequiredForAsyncWork;
    }

    public Instant fetchedAt() {
        return fetchedAt;
    }
}
