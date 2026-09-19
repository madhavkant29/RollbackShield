package com.rollbackshield.release.application;

import com.rollbackshield.audit.AuditAction;
import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.release.domain.ReleaseRepository;
import com.rollbackshield.release.domain.ReleaseState;
import com.rollbackshield.shared.api.ConflictException;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.shared.events.domain.DomainEvent;
import com.rollbackshield.shared.events.domain.EventPublisher;
import com.rollbackshield.workfence.application.WorkFenceApplicationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ReleaseApplicationService {

    private final ReleaseRepository releases;
    private final AuditTrail auditTrail;
    private final WorkFenceApplicationService workFence;
    private final EventPublisher events;

    public ReleaseApplicationService(ReleaseRepository releases, AuditTrail auditTrail,
                                      WorkFenceApplicationService workFence, EventPublisher events) {
        this.releases = releases;
        this.auditTrail = auditTrail;
        this.workFence = workFence;
        this.events = events;
    }

    public Release create(OrganizationId organizationId, ServiceId serviceId,
                           String previousVersionLabel, String candidateVersionLabel) {
        Release release = Release.draft(organizationId, serviceId, previousVersionLabel, candidateVersionLabel);
        releases.save(release);
        auditTrail.append(AuditEvent.of(organizationId.toString(), release.id().toString(), "user",
            AuditAction.RELEASE_CREATED, release.id().toString(),
            previousVersionLabel + " -> " + candidateVersionLabel, null, "DRAFT", Map.of()));
        events.publish(DomainEvent.of("ReleaseCreated", organizationId.toString(), release.id().toString(),
            Map.of("serviceId", serviceId.toString(), "candidateVersionLabel", candidateVersionLabel)));
        return release;
    }

    public Release get(ReleaseId releaseId, OrganizationId callerOrganizationId) {
        Release release = releases.findById(releaseId)
            .orElseThrow(() -> new NotFoundException("RELEASE_NOT_FOUND", "No release " + releaseId));
        if (!release.organizationId().equals(callerOrganizationId)) {
            throw new NotFoundException("RELEASE_NOT_FOUND", "No release " + releaseId);
        }
        return release;
    }

    public List<Release> listForService(ServiceId serviceId) {
        return releases.findByService(serviceId);
    }

    public Release prepare(ReleaseId releaseId) {
        return transition(releaseId, ReleaseState.PREPARING, "user", "preparing release");
    }

    public Release markReady(ReleaseId releaseId) {
        return transition(releaseId, ReleaseState.READY, "user", "release ready");
    }

    /** Invoked by ContractApplicationService once a rollback contract is activated. */
    public Release activateProtectedRollout(ReleaseId releaseId) {
        Release release = transition(releaseId, ReleaseState.PROTECTED_ROLLOUT, "system",
            "rollback contract activated");
        auditTrail.append(AuditEvent.of(release.organizationId().toString(), releaseId.toString(), "system",
            AuditAction.CONTRACT_ACTIVATED, releaseId.toString(),
            "protection active at epoch " + release.epoch(), null, null, Map.of()));
        events.publish(DomainEvent.of("RollbackProtectionActivated", release.organizationId().toString(),
            releaseId.toString(), Map.of("epoch", String.valueOf(release.epoch()))));
        return release;
    }

    /**
     * Full rollback workflow (§14) without a connected runtime: ROLLING_BACK,
     * invalidate the candidate epoch so no pending work can fire its
     * irreversible effect, then ROLLED_BACK. The rollback orchestrator uses
     * the individual steps so it can interleave real provider calls; this
     * method remains the control-plane-only path. Idempotent: re-invoking on
     * an already-ROLLED_BACK release is a conflict, not a silent no-op.
     */
    public Release rollback(ReleaseId releaseId, String reason) {
        Release rollingBack = beginRollback(releaseId, reason);
        invalidateEpoch(rollingBack);
        return completeRollback(releaseId, "epoch invalidated, rollback recorded");
    }

    /** Step 1-2 of rollback: authorize was done by the caller, now lock the state in. */
    public Release beginRollback(ReleaseId releaseId, String reason) {
        Release rollingBack = transition(releaseId, ReleaseState.ROLLING_BACK, "operator", reason);
        events.publish(DomainEvent.of("RollbackStarted", rollingBack.organizationId().toString(),
            releaseId.toString(), Map.of("reason", reason)));
        return rollingBack;
    }

    /** Step 3: no pending candidate work may produce effects after this. */
    public void invalidateEpoch(Release release) {
        workFence.invalidateEpoch(release.id(), release.epoch());
    }

    /** Final step: the rollback actually converged (or was control-plane only). */
    public Release completeRollback(ReleaseId releaseId, String detail) {
        Release before = releases.findById(releaseId)
            .orElseThrow(() -> new NotFoundException("RELEASE_NOT_FOUND", "No release " + releaseId));
        Release rolledBack = transition(releaseId, ReleaseState.ROLLED_BACK, "system", detail);
        auditTrail.append(AuditEvent.of(rolledBack.organizationId().toString(), releaseId.toString(),
            "system", AuditAction.ROLLBACK_COMPLETED, releaseId.toString(), detail,
            before.state().name(), rolledBack.state().name(), Map.of()));
        events.publish(DomainEvent.of("ReleaseRolledBack", rolledBack.organizationId().toString(),
            releaseId.toString(), Map.of("epoch", String.valueOf(rolledBack.epoch()))));
        return rolledBack;
    }

    /** A rollback that did not converge ends FAILED with the exact step; never ROLLED_BACK. */
    public Release failRollback(ReleaseId releaseId, String reason) {
        Release failed = transition(releaseId, ReleaseState.FAILED, "system", reason);
        auditTrail.append(AuditEvent.of(failed.organizationId().toString(), releaseId.toString(),
            "system", AuditAction.ROLLBACK_EXECUTION_FAILED, releaseId.toString(), reason,
            "ROLLING_BACK", "FAILED", Map.of()));
        events.publish(DomainEvent.of("RollbackFailed", failed.organizationId().toString(),
            releaseId.toString(), Map.of("reason", reason)));
        return failed;
    }

    public Release commit(ReleaseId releaseId) {
        Release committing = transition(releaseId, ReleaseState.COMMITTING, "operator", "commit requested");
        events.publish(DomainEvent.of("ReleaseCommitStarted", committing.organizationId().toString(),
            releaseId.toString(), Map.of()));

        Release committed = transition(releaseId, ReleaseState.COMMITTED, "system", "commit finalized");
        auditTrail.append(AuditEvent.of(committed.organizationId().toString(), releaseId.toString(),
            "operator", AuditAction.COMMIT_COMPLETED, releaseId.toString(),
            "rollback window closed", "COMMITTING", "COMMITTED", Map.of()));
        events.publish(DomainEvent.of("ReleaseCommitted", committed.organizationId().toString(),
            releaseId.toString(), Map.of()));

        return committed;
    }

    private Release transition(ReleaseId releaseId, ReleaseState target, String actor, String reason) {
        Release current = releases.findById(releaseId)
            .orElseThrow(() -> new NotFoundException("RELEASE_NOT_FOUND", "No release " + releaseId));

        Release next = current.transitionTo(target); // throws ReleaseTransitionException if invalid

        boolean saved = releases.compareAndSave(next, current.state());
        if (!saved) {
            // Another request changed the release between our read and write.
            throw new ConflictException("STATE_TRANSITION_FAILED",
                "Release " + releaseId + " changed concurrently; retry", Map.of());
        }

        auditTrail.append(AuditEvent.of(next.organizationId().toString(), releaseId.toString(), actor,
            mapAction(target), releaseId.toString(), reason, current.state().name(), target.name(), Map.of()));

        return next;
    }

    private AuditAction mapAction(ReleaseState target) {
        return switch (target) {
            case ROLLING_BACK -> AuditAction.ROLLBACK_REQUESTED;
            case COMMITTING -> AuditAction.COMMIT_REQUESTED;
            default -> AuditAction.RELEASE_STATE_CHANGED; // specific actions are logged by callers above
        };
    }
}
