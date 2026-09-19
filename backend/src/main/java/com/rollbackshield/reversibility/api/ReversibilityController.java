package com.rollbackshield.reversibility.api;

import com.rollbackshield.release.application.ReleaseApplicationService;
import com.rollbackshield.reversibility.application.ReversibilityApplicationService;
import com.rollbackshield.reversibility.domain.PreflightReport;
import com.rollbackshield.reversibility.domain.ReversibilityCheck;
import com.rollbackshield.reversibility.domain.ReversibilityEvidence;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.security.CurrentPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.rollbackshield.reversibility.api.ReversibilityDtos.*;
@RestController
@RequestMapping("/api/v1/releases")
public class ReversibilityController {

    private final ReversibilityApplicationService reversibility;
    private final ReleaseApplicationService releases;

    public ReversibilityController(ReversibilityApplicationService reversibility,
                                   ReleaseApplicationService releases) {
        this.reversibility = reversibility;
        this.releases = releases;
    }

    @GetMapping("/{releaseId}/reversibility")
    public ReversibilityReportResponse get(@PathVariable String releaseId) {
        OrganizationId callerOrg = OrganizationId.of(CurrentPrincipal.get().organizationId());
        // Throws NotFoundException (never leaking existence) if this release
        // belongs to a different organization -- §30/§37.
        releases.get(ReleaseId.of(releaseId), callerOrg);

        PreflightReport report = reversibility.evaluate(ReleaseId.of(releaseId));
        return new ReversibilityReportResponse(releaseId, report.status().name(),
            report.verdict().name(),
            report.checks().stream().map(ReversibilityController::toDto).toList(),
            report.evidence().stream().map(ReversibilityController::toDto).toList(),
            report.blockerPaths().stream().map(ReversibilityController::toDto).toList(),
            report.evaluatedAt().toString());
    }

    private static BlockerPathDto toDto(PreflightReport.BlockerPath blockerPath) {
        return new BlockerPathDto(blockerPath.code(), blockerPath.severity(), blockerPath.description(),
            blockerPath.path().stream().map(ReversibilityController::toDto).toList());
    }

    private static CheckDto toDto(ReversibilityCheck check) {
        return new CheckDto(check.name(), check.passed(),
            check.passed() ? null : check.blocker().code(),
            check.passed() ? null : check.blocker().description(),
            check.passed() ? null : check.blocker().severity().name());
    }

    private static EvidenceDto toDto(ReversibilityEvidence evidence) {
        return new EvidenceDto(evidence.subject(), evidence.relation(), evidence.object(),
            evidence.source(), evidence.detail());
    }
}
