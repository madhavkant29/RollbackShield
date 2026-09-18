package com.rollbackshield.contract.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public final class ContractDtos {

    private ContractDtos() {
    }

    /**
     * One flat shape for all rule types, discriminated by `type`. This is
     * the exact wire format the SDK's HttpPolicySource parses -- kept flat
     * (rather than JSON polymorphism) so the SDK's hand-rolled, dependency-
     * free JSON reader doesn't need subtype dispatch.
     */
    public record RuleDto(
        @NotBlank String type,
        @NotBlank String entity,
        @NotBlank String field,
        List<String> previousVersionSupports,
        Boolean previousVersionAllowsNull,
        Double min,
        Double max,
        List<String> forbiddenValues
    ) {
    }

    public record CreateContractRequest(
        long rollbackWindowSeconds,
        boolean candidateEpochRequiredForAsyncWork,
        @NotEmpty List<RuleDto> rules
    ) {
    }

    public record ContractResponse(
        String contractId,
        String releaseId,
        int contractVersion,
        long policyVersion,
        String status
    ) {
    }

    public record PolicyResponse(
        String contractId,
        int contractVersion,
        long policyVersion,
        boolean candidateEpochRequiredForAsyncWork,
        List<RuleDto> rules
    ) {
    }
}
