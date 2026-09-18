package com.rollbackshield.workfence.application;

import com.rollbackshield.audit.adapter.InMemoryAuditTrail;
import com.rollbackshield.release.adapter.InMemoryReleaseRepository;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.release.domain.ReleaseState;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.workfence.adapter.InMemoryEpochRegistry;
import com.rollbackshield.workfence.adapter.InMemoryRedemptionLedger;
import com.rollbackshield.workfence.adapter.InMemoryWorkQueue;
import com.rollbackshield.workfence.domain.RedeemOutcome;
import com.rollbackshield.workfence.domain.WorkJob;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The §12 invariant in isolation, with no Spring context: the first
 * committed redemption for a jobId is final, regardless of later epoch
 * changes. `ReleaseLifecycleIntegrationTest` covers the same path over
 * HTTP; this pins the decision rule itself.
 */
class WorkFenceApplicationServiceTest {

    private final InMemoryReleaseRepository releases = new InMemoryReleaseRepository();
    private final WorkFenceApplicationService workFence = new WorkFenceApplicationService(
        releases, new InMemoryWorkQueue(), new InMemoryAuditTrail(), event -> { },
        new InMemoryEpochRegistry(), new InMemoryRedemptionLedger());

    private Release protectedRelease() {
        Release release = Release.draft(OrganizationId.newId(), ServiceId.newId(), "v1", "v2")
            .transitionTo(ReleaseState.PREPARING)
            .transitionTo(ReleaseState.READY)
            .transitionTo(ReleaseState.PROTECTED_ROLLOUT);
        releases.save(release);
        return release;
    }

    @Test
    void executeOutcomeIsFinalEvenAfterTheEpochIsLaterInvalidated() {
        Release release = protectedRelease();
        WorkJob job = workFence.enqueueWork(release.id(), "ISSUE_PARTIAL_REFUND_WEBHOOK", "order-1");
        assertThat(job.releaseEpoch()).isEqualTo(1);

        assertThat(workFence.redeem(job.jobId(), release.id(), 1)).isEqualTo(RedeemOutcome.EXECUTE);

        workFence.invalidateEpoch(release.id(), 1);

        // The epoch is now invalid, but this job already committed EXECUTE.
        assertThat(workFence.redeem(job.jobId(), release.id(), 1)).isEqualTo(RedeemOutcome.EXECUTE);
    }

    @Test
    void firstRedemptionAfterInvalidationCommitsCancelAndStaysCancel() {
        Release release = protectedRelease();
        WorkJob job = workFence.enqueueWork(release.id(), "ISSUE_PARTIAL_REFUND_WEBHOOK", "order-1");

        workFence.invalidateEpoch(release.id(), 1);

        assertThat(workFence.redeem(job.jobId(), release.id(), 1)).isEqualTo(RedeemOutcome.CANCEL);
        assertThat(workFence.redeem(job.jobId(), release.id(), 1)).isEqualTo(RedeemOutcome.CANCEL);
    }
}
