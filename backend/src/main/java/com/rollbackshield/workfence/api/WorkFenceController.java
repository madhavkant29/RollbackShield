package com.rollbackshield.workfence.api;

import com.rollbackshield.release.application.ReleaseApplicationService;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.security.CurrentPrincipal;
import com.rollbackshield.workfence.application.WorkFenceApplicationService;
import com.rollbackshield.workfence.domain.RedeemOutcome;
import com.rollbackshield.workfence.domain.WorkJob;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.rollbackshield.workfence.api.WorkFenceDtos.*;

@RestController
@RequestMapping("/api/v1")
public class WorkFenceController {

    private final WorkFenceApplicationService workFence;
    private final ReleaseApplicationService releases;

    public WorkFenceController(WorkFenceApplicationService workFence, ReleaseApplicationService releases) {
        this.workFence = workFence;
        this.releases = releases;
    }

    @PostMapping("/releases/{releaseId}/work")
    public ResponseEntity<WorkJobResponse> enqueue(@PathVariable String releaseId,
                                                     @Valid @RequestBody EnqueueWorkRequest request) {
        OrganizationId callerOrg = OrganizationId.of(CurrentPrincipal.get().organizationId());
        releases.get(ReleaseId.of(releaseId), callerOrg); // §30/§37

        WorkJob job = workFence.enqueueWork(ReleaseId.of(releaseId), request.jobType(), request.payload());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(job));
    }

    /**
     * Polled by worker processes, not by tenant callers -- unlike every
     * other endpoint in this class, this one is NOT organization-scoped: a
     * worker legitimately needs to see queued work across all tenants it's
     * responsible for. Treat this endpoint as needing its own
     * worker-identity auth model (a service credential, not a user JWT)
     * before it's exposed outside a trusted network -- tracked in
     * docs/product/LIMITATIONS.md, not silently assumed safe.
     */
    @GetMapping("/work/poll")
    public List<WorkJobResponse> poll(@RequestParam(defaultValue = "10") int max) {
        return workFence.poll(max).stream().map(WorkFenceController::toResponse).toList();
    }

    /**
     * Atomic check-and-redeem: called by the worker exactly once per delivery
     * attempt, immediately before it would perform the job's irreversible
     * side effect. Safe to call more than once for the same jobId (§12).
     * Same worker-trust caveat as poll() above applies here.
     */
    @PostMapping("/work/{jobId}/redeem")
    public RedeemResponse redeem(@PathVariable String jobId, @Valid @RequestBody RedeemRequest request) {
        RedeemOutcome outcome = workFence.redeem(jobId, ReleaseId.of(request.releaseId()), request.releaseEpoch());
        return new RedeemResponse(jobId, outcome.name());
    }

    private static WorkJobResponse toResponse(WorkJob job) {
        return new WorkJobResponse(job.jobId(), job.releaseId().toString(), job.releaseEpoch(),
            job.jobType(), job.payload());
    }
}
