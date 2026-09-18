package com.rollbackshield.release.api;

import com.rollbackshield.release.application.ReleaseApplicationService;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.shared.security.CurrentPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.rollbackshield.release.api.ReleaseDtos.*;

@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseController {

    private final ReleaseApplicationService releases;

    public ReleaseController(ReleaseApplicationService releases) {
        this.releases = releases;
    }

    @PostMapping
    public ResponseEntity<ReleaseResponse> create(@Valid @RequestBody CreateReleaseRequest request) {
        OrganizationId org = OrganizationId.of(CurrentPrincipal.get().organizationId());
        Release release = releases.create(org, ServiceId.of(request.serviceId()),
            request.previousVersionLabel(), request.candidateVersionLabel());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(release));
    }

    @GetMapping("/{releaseId}")
    public ReleaseResponse get(@PathVariable String releaseId) {
        OrganizationId org = OrganizationId.of(CurrentPrincipal.get().organizationId());
        return toResponse(releases.get(ReleaseId.of(releaseId), org));
    }

    @GetMapping
    public List<ReleaseResponse> listForService(@RequestParam String serviceId) {
        return releases.listForService(ServiceId.of(serviceId)).stream()
            .map(ReleaseController::toResponse).toList();
    }

    @PostMapping("/{releaseId}/prepare")
    public ReleaseResponse prepare(@PathVariable String releaseId) {
        return toResponse(releases.prepare(ReleaseId.of(releaseId)));
    }

    @PostMapping("/{releaseId}/ready")
    public ReleaseResponse markReady(@PathVariable String releaseId) {
        return toResponse(releases.markReady(ReleaseId.of(releaseId)));
    }

    @PostMapping("/{releaseId}/rollback")
    public ReleaseResponse rollback(@PathVariable String releaseId,
                                     @RequestBody(required = false) RollbackRequest request) {
        String reason = request != null && request.reason() != null ? request.reason() : "operator requested";
        return toResponse(releases.rollback(ReleaseId.of(releaseId), reason));
    }

    @PostMapping("/{releaseId}/commit")
    public ReleaseResponse commit(@PathVariable String releaseId) {
        return toResponse(releases.commit(ReleaseId.of(releaseId)));
    }

    private static ReleaseResponse toResponse(Release release) {
        return new ReleaseResponse(release.id().toString(), release.organizationId().toString(),
            release.serviceId().toString(), release.previousVersionLabel(), release.candidateVersionLabel(),
            release.state().name(), release.epoch());
    }
}
