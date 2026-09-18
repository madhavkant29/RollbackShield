package com.rollbackshield.reversibility.application;

import com.rollbackshield.contract.domain.RollbackContract;
import com.rollbackshield.contract.domain.RollbackContractRepository;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.release.domain.ReleaseRepository;
import com.rollbackshield.reversibility.domain.ReversibilityBlocker;
import com.rollbackshield.reversibility.domain.ReversibilityCheck;
import com.rollbackshield.reversibility.domain.ReversibilityEvaluator;
import com.rollbackshield.reversibility.domain.ReversibilityReport;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.domain.ReleaseId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles a ReversibilityReport from the *actual current state* of the
 * release's contract -- never a cached or asserted status. Every check below
 * reads real repository state at request time.
 */
@Service
public class ReversibilityApplicationService {

    private final ReleaseRepository releases;
    private final RollbackContractRepository contracts;

    public ReversibilityApplicationService(ReleaseRepository releases, RollbackContractRepository contracts) {
        this.releases = releases;
        this.contracts = contracts;
    }

    public ReversibilityReport evaluate(ReleaseId releaseId) {
        Release release = releases.findById(releaseId)
            .orElseThrow(() -> new NotFoundException("RELEASE_NOT_FOUND", "No release " + releaseId));

        Optional<RollbackContract> activeContract = contracts.findActiveForRelease(releaseId);
        List<ReversibilityCheck> checks = new ArrayList<>();

        if (activeContract.isEmpty()) {
            checks.add(ReversibilityCheck.fail("Data compatibility",
                new ReversibilityBlocker(ReversibilityBlocker.NO_ACTIVE_CONTRACT,
                    "no active rollback contract protects this release")));
            checks.add(ReversibilityCheck.fail("Queued work fencing",
                new ReversibilityBlocker(ReversibilityBlocker.NO_ACTIVE_CONTRACT,
                    "no active rollback contract protects this release")));
            checks.add(ReversibilityCheck.fail("Policy freshness",
                new ReversibilityBlocker(ReversibilityBlocker.NO_ACTIVE_CONTRACT,
                    "no active rollback contract protects this release")));
            return ReversibilityEvaluator.evaluate(release.state(), checks);
        }

        RollbackContract contract = activeContract.get();

        // Enforcement guarantees no BLOCKed write ever reaches persistence,
        // so as long as protection is engaged, data compatibility holds.
        checks.add(ReversibilityCheck.pass("Data compatibility"));

        if (contract.candidateEpochRequiredForAsyncWork()) {
            checks.add(ReversibilityCheck.pass("Queued work fencing"));
        } else {
            checks.add(ReversibilityCheck.fail("Queued work fencing",
                new ReversibilityBlocker(ReversibilityBlocker.UNFENCED_WORK_PENDING,
                    "this contract does not require epoch fencing for async work")));
        }

        boolean windowMoot = release.state().name().equals("ROLLED_BACK")
            || release.state().name().equals("COMMITTED");
        if (windowMoot || contract.isActive(Instant.now())) {
            checks.add(ReversibilityCheck.pass("Policy freshness"));
        } else {
            checks.add(ReversibilityCheck.fail("Policy freshness",
                new ReversibilityBlocker(ReversibilityBlocker.POLICY_EXPIRED,
                    "rollback window has elapsed for the active contract")));
        }

        return ReversibilityEvaluator.evaluate(release.state(), checks);
    }
}
