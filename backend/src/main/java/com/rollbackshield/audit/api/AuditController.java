package com.rollbackshield.audit.api;

import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
import com.rollbackshield.release.application.ReleaseApplicationService;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.security.CurrentPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.rollbackshield.audit.api.AuditDtos.*;

@RestController
@RequestMapping("/api/v1/releases")
public class AuditController {

    private final AuditTrail auditTrail;
    private final ReleaseApplicationService releases;

    public AuditController(AuditTrail auditTrail, ReleaseApplicationService releases) {
        this.auditTrail = auditTrail;
        this.releases = releases;
    }

    @GetMapping("/{releaseId}/audit")
    public List<AuditEventResponse> list(@PathVariable String releaseId) {
        OrganizationId callerOrg = OrganizationId.of(CurrentPrincipal.get().organizationId());
        releases.get(ReleaseId.of(releaseId), callerOrg); // §30/§37 -- throws if not this caller's release

        return auditTrail.listForRelease(releaseId).stream().map(AuditController::toDto).toList();
    }

    private static AuditEventResponse toDto(AuditEvent event) {
        return new AuditEventResponse(event.eventId(), event.actor(), event.action().name(),
            event.target(), event.timestamp(), event.reason(), event.previousState(), event.newState());
    }
}
