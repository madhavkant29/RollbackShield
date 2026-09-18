package com.rollbackshield.workfence.application;

import com.rollbackshield.audit.AuditAction;
import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.release.domain.ReleaseRepository;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.events.domain.DomainEvent;
import com.rollbackshield.shared.events.domain.EventPublisher;
import com.rollbackshield.workfence.domain.EpochRegistry;
import com.rollbackshield.workfence.domain.RedeemOutcome;
import com.rollbackshield.workfence.domain.RedemptionLedger;
import com.rollbackshield.workfence.domain.WorkJob;
import com.rollbackshield.workfence.domain.WorkQueue;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the epoch registry and job redemption ledger. Redemption is the
 * atomic operation from §12: the first redeem() call for a jobId decides
 * EXECUTE or CANCEL based on epoch validity at that instant; every
 * subsequent call for the same jobId -- however many, regardless of any
 * later epoch change -- observes that same committed outcome. This is what
 * makes the irreversible side effect safe under SQS at-least-once delivery.
 */
@Service
public class WorkFenceApplicationService {

    private final ReleaseRepository releases;
    private final WorkQueue workQueue;
    private final AuditTrail auditTrail;
    private final EventPublisher events;
    private final EpochRegistry epochRegistry;
    private final RedemptionLedger redemptionLedger;

    public WorkFenceApplicationService(ReleaseRepository releases, WorkQueue workQueue, AuditTrail auditTrail,
                                        EventPublisher events, EpochRegistry epochRegistry,
                                        RedemptionLedger redemptionLedger) {
        this.releases = releases;
        this.workQueue = workQueue;
        this.auditTrail = auditTrail;
        this.events = events;
        this.epochRegistry = epochRegistry;
        this.redemptionLedger = redemptionLedger;
    }

    public WorkJob enqueueWork(ReleaseId releaseId, String jobType, String payload) {
        Release release = releases.findById(releaseId)
            .orElseThrow(() -> new NotFoundException("RELEASE_NOT_FOUND", "No release " + releaseId));

        WorkJob job = new WorkJob(UUID.randomUUID().toString(), releaseId, release.epoch(), jobType,
            Instant.now(), payload);
        workQueue.enqueue(job);

        auditTrail.append(AuditEvent.of(release.organizationId().toString(), releaseId.toString(),
            "service:" + release.serviceId(), AuditAction.WORK_ENQUEUED, job.jobId(),
            "queued under epoch " + release.epoch(), null, null, Map.of("jobType", jobType)));

        return job;
    }

    public List<WorkJob> poll(int maxMessages) {
        return workQueue.receive(maxMessages);
    }

    /** Called by ReleaseApplicationService as part of the rollback workflow. */
    public void invalidateEpoch(ReleaseId releaseId, long epoch) {
        epochRegistry.invalidate(releaseId, epoch);
        releases.findById(releaseId).ifPresent(release -> {
            auditTrail.append(AuditEvent.of(release.organizationId().toString(), releaseId.toString(),
                "system", AuditAction.EPOCH_INVALIDATED, String.valueOf(epoch),
                "rollback invalidated candidate epoch", null, null, Map.of()));
            events.publish(DomainEvent.of("WorkEpochInvalidated", release.organizationId().toString(),
                releaseId.toString(), Map.of("epoch", String.valueOf(epoch))));
        });
    }

    public RedeemOutcome redeem(String jobId, ReleaseId releaseId, long releaseEpoch) {
        RedeemOutcome candidate = epochRegistry.isValid(releaseId, releaseEpoch)
            ? RedeemOutcome.EXECUTE
            : RedeemOutcome.CANCEL;
        RedeemOutcome outcome = redemptionLedger.commitIfAbsent(jobId, releaseId, candidate);

        releases.findById(releaseId).ifPresent(release ->
            auditTrail.append(AuditEvent.of(release.organizationId().toString(), releaseId.toString(),
                "worker", outcome == RedeemOutcome.EXECUTE ? AuditAction.WORK_EXECUTED : AuditAction.WORK_CANCELLED,
                jobId, "redeem outcome " + outcome, null, outcome.name(), Map.of())));

        return outcome;
    }
}
