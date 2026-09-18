package com.rollbackshield.contract.application;

import com.rollbackshield.audit.AuditAction;
import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
import com.rollbackshield.contract.api.ContractDtos.RuleDto;
import com.rollbackshield.contract.domain.CompatibilityRule;
import com.rollbackshield.contract.domain.RollbackContract;
import com.rollbackshield.contract.domain.RollbackContractRepository;
import com.rollbackshield.release.application.ReleaseApplicationService;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.shared.api.ConflictException;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.domain.ContractId;
import com.rollbackshield.shared.domain.PolicyVersion;
import com.rollbackshield.shared.domain.ReleaseId;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ContractApplicationService {

    private final RollbackContractRepository contracts;
    private final ReleaseApplicationService releaseApplicationService;
    private final com.rollbackshield.release.domain.ReleaseRepository releases;
    private final AuditTrail auditTrail;

    public ContractApplicationService(RollbackContractRepository contracts,
                                       ReleaseApplicationService releaseApplicationService,
                                       com.rollbackshield.release.domain.ReleaseRepository releases,
                                       AuditTrail auditTrail) {
        this.contracts = contracts;
        this.releaseApplicationService = releaseApplicationService;
        this.releases = releases;
        this.auditTrail = auditTrail;
    }

    /**
     * Creates a contract in DRAFT then immediately activates it, which also
     * drives the release from READY into PROTECTED_ROLLOUT (§7 -- a release
     * has a versioned RollbackContract; activation is what makes protection
     * live).
     */
    public RollbackContract createAndActivate(ReleaseId releaseId, Duration rollbackWindow,
                                                boolean candidateEpochRequired, List<RuleDto> ruleDtos) {
        Release release = releases.findById(releaseId)
            .orElseThrow(() -> new NotFoundException("RELEASE_NOT_FOUND", "No release " + releaseId));

        List<CompatibilityRule> rules = ruleDtos.stream().map(ContractApplicationService::toDomainRule).toList();

        contracts.findActiveForRelease(releaseId).ifPresent(existing -> {
            throw new ConflictException("CONTRACT_VERSION_CONFLICT",
                "Release " + releaseId + " already has an active contract", Map.of());
        });

        String contentHash = hash(rules.toString());
        RollbackContract contract = new RollbackContract(
            ContractId.newId(), release.organizationId(), release.serviceId(), releaseId,
            1, new PolicyVersion(1), Instant.now(), Instant.now(), rollbackWindow, rules,
            candidateEpochRequired, RollbackContract.ContractStatus.ACTIVE, contentHash);

        contracts.save(contract);

        auditTrail.append(AuditEvent.of(release.organizationId().toString(), releaseId.toString(), "user",
            AuditAction.CONTRACT_CREATED, contract.contractId().toString(),
            rules.size() + " compatibility rules", null, "ACTIVE", Map.of()));

        // Drives READY -> PROTECTED_ROLLOUT centrally through ReleaseApplicationService,
        // never by writing release state directly from this module.
        releaseApplicationService.activateProtectedRollout(releaseId);

        return contract;
    }

    public RollbackContract get(ContractId contractId) {
        return contracts.findById(contractId)
            .orElseThrow(() -> new NotFoundException("INVALID_ROLLBACK_CONTRACT", "No contract " + contractId));
    }

    public static CompatibilityRule toDomainRule(RuleDto dto) {
        return switch (dto.type()) {
            case "ENUM_ALLOWED_VALUES" -> new CompatibilityRule.EnumAllowedValues(
                dto.entity(), dto.field(), Set.copyOf(dto.previousVersionSupports()));
            case "NULLABILITY" -> new CompatibilityRule.Nullability(
                dto.entity(), dto.field(), Boolean.TRUE.equals(dto.previousVersionAllowsNull()));
            case "NUMERIC_RANGE" -> new CompatibilityRule.NumericRange(
                dto.entity(), dto.field(), dto.min(), dto.max());
            case "REQUIRED_FIELD" -> new CompatibilityRule.RequiredField(dto.entity(), dto.field());
            case "FORBIDDEN_VALUE" -> new CompatibilityRule.ForbiddenValue(
                dto.entity(), dto.field(), Set.copyOf(dto.forbiddenValues()));
            default -> throw new com.rollbackshield.shared.api.ConflictException(
                "INVALID_ROLLBACK_CONTRACT", "Unknown rule type: " + dto.type(), Map.of());
        };
    }

    public static RuleDto toDto(CompatibilityRule rule) {
        return switch (rule) {
            case CompatibilityRule.EnumAllowedValues r -> new RuleDto("ENUM_ALLOWED_VALUES", r.entity(),
                r.field(), List.copyOf(r.previousVersionSupports()), null, null, null, null);
            case CompatibilityRule.Nullability r -> new RuleDto("NULLABILITY", r.entity(), r.field(),
                null, r.previousVersionAllowsNull(), null, null, null);
            case CompatibilityRule.NumericRange r -> new RuleDto("NUMERIC_RANGE", r.entity(), r.field(),
                null, null, r.min(), r.max(), null);
            case CompatibilityRule.RequiredField r -> new RuleDto("REQUIRED_FIELD", r.entity(), r.field(),
                null, null, null, null, null);
            case CompatibilityRule.ForbiddenValue r -> new RuleDto("FORBIDDEN_VALUE", r.entity(), r.field(),
                null, null, null, null, List.copyOf(r.forbiddenValues()));
        };
    }

    private static String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
