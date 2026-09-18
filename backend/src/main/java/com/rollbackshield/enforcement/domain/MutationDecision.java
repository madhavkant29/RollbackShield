package com.rollbackshield.enforcement.domain;

/**
 * Result of evaluating a proposed state mutation against the active rollback
 * contract's compatibility rules. Deterministic only — no scoring, no LLM.
 */
public record MutationDecision(
    Decision decision,
    ReasonCode reasonCode,
    String entity,
    String field,
    String attemptedValue,
    String contractVersion,
    String policyVersion
) {

    public enum Decision {
        ALLOW,
        BLOCK,
        UNKNOWN
    }

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

    public static MutationDecision allow(String entity, String field, String value,
                                          String contractVersion, String policyVersion) {
        return new MutationDecision(Decision.ALLOW, ReasonCode.COMPATIBLE, entity, field, value,
            contractVersion, policyVersion);
    }

    public static MutationDecision block(ReasonCode reason, String entity, String field, String value,
                                          String contractVersion, String policyVersion) {
        return new MutationDecision(Decision.BLOCK, reason, entity, field, value,
            contractVersion, policyVersion);
    }
}
