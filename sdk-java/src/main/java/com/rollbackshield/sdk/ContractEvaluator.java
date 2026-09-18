package com.rollbackshield.sdk;

import java.util.List;

/**
 * Deterministic evaluation of a single {@link MutationRequest} against an
 * indexed {@link PolicySnapshot}. No I/O, no locks beyond what
 * {@link PolicyCache} already handles by publishing an immutable snapshot —
 * this class is a pure function over its inputs and is safe to call from any
 * thread without synchronization.
 */
public final class ContractEvaluator {

    private ContractEvaluator() {
    }

    public static MutationDecision evaluate(MutationRequest request, PolicySnapshot snapshot) {
        List<SdkCompatibilityRule> rules = snapshot.rulesFor(request.entity(), request.field());

        for (SdkCompatibilityRule rule : rules) {
            MutationDecision blocked = checkRule(rule, request, snapshot);
            if (blocked != null) {
                return blocked;
            }
        }

        return MutationDecision.allow(request, snapshot);
    }

    private static MutationDecision checkRule(SdkCompatibilityRule rule, MutationRequest request,
                                                PolicySnapshot snapshot) {
        return switch (rule) {
            case SdkCompatibilityRule.EnumAllowedValues r -> {
                String value = request.attemptedValue();
                if (value != null && !r.previousVersionSupports().contains(value)) {
                    yield MutationDecision.block(
                        MutationDecision.ReasonCode.CROSSES_ROLLBACK_HORIZON, request, snapshot);
                }
                yield null;
            }
            case SdkCompatibilityRule.Nullability r -> {
                if (request.attemptedValue() == null && !r.previousVersionAllowsNull()) {
                    yield MutationDecision.block(
                        MutationDecision.ReasonCode.NULLABILITY_VIOLATION, request, snapshot);
                }
                yield null;
            }
            case SdkCompatibilityRule.NumericRange r -> {
                String value = request.attemptedValue();
                if (value == null) {
                    yield null; // nullability is a separate rule's concern
                }
                double parsed;
                try {
                    parsed = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    yield MutationDecision.block(
                        MutationDecision.ReasonCode.NUMERIC_RANGE_VIOLATION, request, snapshot);
                }
                if (parsed < r.min() || parsed > r.max()) {
                    yield MutationDecision.block(
                        MutationDecision.ReasonCode.NUMERIC_RANGE_VIOLATION, request, snapshot);
                }
                yield null;
            }
            case SdkCompatibilityRule.RequiredField r -> {
                if (request.attemptedValue() == null) {
                    yield MutationDecision.block(
                        MutationDecision.ReasonCode.REQUIRED_FIELD_MISSING, request, snapshot);
                }
                yield null;
            }
            case SdkCompatibilityRule.ForbiddenValue r -> {
                String value = request.attemptedValue();
                if (value != null && r.forbiddenValues().contains(value)) {
                    yield MutationDecision.block(
                        MutationDecision.ReasonCode.FORBIDDEN_VALUE, request, snapshot);
                }
                yield null;
            }
        };
    }
}
