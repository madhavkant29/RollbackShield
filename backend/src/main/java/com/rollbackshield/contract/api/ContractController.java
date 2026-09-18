package com.rollbackshield.contract.api;

import com.rollbackshield.contract.application.ContractApplicationService;
import com.rollbackshield.contract.domain.RollbackContract;
import com.rollbackshield.release.application.ReleaseApplicationService;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.domain.ContractId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.security.CurrentPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

import static com.rollbackshield.contract.api.ContractDtos.*;

@RestController
@RequestMapping("/api/v1")
public class ContractController {

    private final ContractApplicationService contracts;
    private final ReleaseApplicationService releases;

    public ContractController(ContractApplicationService contracts, ReleaseApplicationService releases) {
        this.contracts = contracts;
        this.releases = releases;
    }

    @PostMapping("/releases/{releaseId}/contracts")
    public ResponseEntity<ContractResponse> createAndActivate(@PathVariable String releaseId,
                                                                @Valid @RequestBody CreateContractRequest request) {
        OrganizationId callerOrg = OrganizationId.of(CurrentPrincipal.get().organizationId());
        releases.get(ReleaseId.of(releaseId), callerOrg); // §30/§37 -- must own the release to contract it

        RollbackContract contract = contracts.createAndActivate(
            ReleaseId.of(releaseId),
            Duration.ofSeconds(request.rollbackWindowSeconds()),
            request.candidateEpochRequiredForAsyncWork(),
            request.rules());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(contract));
    }

    @GetMapping("/contracts/{contractId}")
    public ContractResponse get(@PathVariable String contractId) {
        OrganizationId callerOrg = OrganizationId.of(CurrentPrincipal.get().organizationId());
        RollbackContract contract = contracts.get(ContractId.of(contractId));
        if (!contract.organizationId().equals(callerOrg)) {
            throw new NotFoundException("INVALID_ROLLBACK_CONTRACT", "No contract " + contractId);
        }
        return toResponse(contract);
    }

    /**
     * The one endpoint the SDK's PolicyCache calls, and only from its
     * background refresh -- never from RollbackGuard.evaluate() on the
     * mutation hot path. See sdk-java's HttpPolicySource.
     *
     * Deliberately NOT organization-scoped by a user JWT: the SDK runs
     * inside the protected application's own process, not a browser
     * session, and HttpPolicySource sends no Authorization header today.
     * A contractId is an unguessable UUID, which is thin protection at
     * best -- a per-contract fetch credential (API key or mTLS) is the
     * right fix and is tracked in docs/product/LIMITATIONS.md, not solved
     * here.
     */
    @GetMapping("/contracts/{contractId}/policy")
    public PolicyResponse getPolicy(@PathVariable String contractId) {
        RollbackContract contract = contracts.get(ContractId.of(contractId));
        return new PolicyResponse(
            contract.contractId().toString(),
            contract.contractVersion(),
            contract.policyVersion().value(),
            contract.candidateEpochRequiredForAsyncWork(),
            contract.rules().stream().map(ContractApplicationService::toDto).toList());
    }

    private static ContractResponse toResponse(RollbackContract contract) {
        return new ContractResponse(contract.contractId().toString(), contract.releaseId().toString(),
            contract.contractVersion(), contract.policyVersion().value(), contract.status().name());
    }
}
