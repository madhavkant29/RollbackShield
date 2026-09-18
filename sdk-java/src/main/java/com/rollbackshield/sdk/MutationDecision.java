package com.rollbackshield.sdk;

public record MutationDecision(
    Decision decision,
    ReasonCode reasonCode,
    String entity,
    String field,
    String attemptedValue,
    int contractVersion,
    long policyVersion
) {

    public enum Decision { ALLOW, BLOCK, UNKNOWN }

    public enum ReasonCode {
        COMPATIBLE,
        CROSSES_ROLLBACK_HORIZON,
        NULLABILITY_VIOLATION,
        NUMERIC_RANGE_VIOLATION,
        REQUIRED_FIELD_MISSING,
        FORBIDDEN_VALUE,
        POLICY_MISSING,
        POLICY_INVALID,
        POLICY_EXPIRED
    }

    static MutationDecision allow(MutationRequest req, PolicySnapshot snapshot) {
        return new MutationDecision(Decision.ALLOW, ReasonCode.COMPATIBLE, req.entity(), req.field(),
            req.attemptedValue(), snapshot.contractVersion(), snapshot.policyVersion());
    }

    static MutationDecision block(ReasonCode reason, MutationRequest req, PolicySnapshot snapshot) {
        return new MutationDecision(Decision.BLOCK, reason, req.entity(), req.field(),
            req.attemptedValue(), snapshot.contractVersion(), snapshot.policyVersion());
    }

    static MutationDecision unknown(ReasonCode reason, MutationRequest req) {
        return new MutationDecision(Decision.UNKNOWN, reason, req.entity(), req.field(),
            req.attemptedValue(), -1, -1);
    }
}
