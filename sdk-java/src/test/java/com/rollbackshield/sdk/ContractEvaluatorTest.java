package com.rollbackshield.sdk;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContractEvaluatorTest {

    private PolicySnapshot snapshotWith(SdkCompatibilityRule... rules) {
        return new PolicySnapshot("contract-1", 2, 3L, true, Instant.now(), List.of(rules));
    }

    @Test
    void blocksEnumValueThePreviousReleaseCannotUnderstand() {
        var rule = new SdkCompatibilityRule.EnumAllowedValues(
            "Order", "status", Set.of("CREATED", "PAID", "CANCELLED", "REFUNDED"));
        var snapshot = snapshotWith(rule);

        var decision = ContractEvaluator.evaluate(
            new MutationRequest("Order", "status", "PARTIALLY_REFUNDED"), snapshot);

        assertThat(decision.decision()).isEqualTo(MutationDecision.Decision.BLOCK);
        assertThat(decision.reasonCode())
            .isEqualTo(MutationDecision.ReasonCode.CROSSES_ROLLBACK_HORIZON);
    }

    @Test
    void allowsEnumValueThePreviousReleaseUnderstands() {
        var rule = new SdkCompatibilityRule.EnumAllowedValues(
            "Order", "status", Set.of("CREATED", "PAID", "CANCELLED", "REFUNDED"));
        var snapshot = snapshotWith(rule);

        var decision = ContractEvaluator.evaluate(
            new MutationRequest("Order", "status", "PAID"), snapshot);

        assertThat(decision.decision()).isEqualTo(MutationDecision.Decision.ALLOW);
    }

    @Test
    void blocksNullWhenPreviousReleaseDoesNotAllowNull() {
        var rule = new SdkCompatibilityRule.Nullability("Order", "customerId", false);
        var snapshot = snapshotWith(rule);

        var decision = ContractEvaluator.evaluate(
            new MutationRequest("Order", "customerId", null), snapshot);

        assertThat(decision.decision()).isEqualTo(MutationDecision.Decision.BLOCK);
        assertThat(decision.reasonCode())
            .isEqualTo(MutationDecision.ReasonCode.NULLABILITY_VIOLATION);
    }

    @Test
    void blocksValueOutsideNumericRange() {
        var rule = new SdkCompatibilityRule.NumericRange("Order", "discountPercent", 0, 100);
        var snapshot = snapshotWith(rule);

        var decision = ContractEvaluator.evaluate(
            new MutationRequest("Order", "discountPercent", "150"), snapshot);

        assertThat(decision.decision()).isEqualTo(MutationDecision.Decision.BLOCK);
        assertThat(decision.reasonCode())
            .isEqualTo(MutationDecision.ReasonCode.NUMERIC_RANGE_VIOLATION);
    }

    @Test
    void blocksMissingRequiredField() {
        var rule = new SdkCompatibilityRule.RequiredField("Order", "shippingAddress");
        var snapshot = snapshotWith(rule);

        var decision = ContractEvaluator.evaluate(
            new MutationRequest("Order", "shippingAddress", null), snapshot);

        assertThat(decision.decision()).isEqualTo(MutationDecision.Decision.BLOCK);
        assertThat(decision.reasonCode())
            .isEqualTo(MutationDecision.ReasonCode.REQUIRED_FIELD_MISSING);
    }

    @Test
    void blocksExplicitlyForbiddenValue() {
        var rule = new SdkCompatibilityRule.ForbiddenValue("Order", "status", Set.of("VOID_LEGACY"));
        var snapshot = snapshotWith(rule);

        var decision = ContractEvaluator.evaluate(
            new MutationRequest("Order", "status", "VOID_LEGACY"), snapshot);

        assertThat(decision.decision()).isEqualTo(MutationDecision.Decision.BLOCK);
        assertThat(decision.reasonCode()).isEqualTo(MutationDecision.ReasonCode.FORBIDDEN_VALUE);
    }

    @Test
    void allowsMutationsOnFieldsWithNoRules() {
        var snapshot = snapshotWith();

        var decision = ContractEvaluator.evaluate(
            new MutationRequest("Order", "notes", "anything"), snapshot);

        assertThat(decision.decision()).isEqualTo(MutationDecision.Decision.ALLOW);
    }
}
