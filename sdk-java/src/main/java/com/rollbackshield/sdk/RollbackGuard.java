package com.rollbackshield.sdk;

import java.util.Objects;

/**
 * The primary object application code interacts with:
 *
 * <pre>
 *   MutationDecision decision = guard.evaluate(
 *       new MutationRequest("Order", "status", "PARTIALLY_REFUNDED"));
 *   if (decision.decision() == MutationDecision.Decision.BLOCK) {
 *       throw new IncompatibleMutationException(decision);
 *   }
 * </pre>
 *
 * Responsibilities:
 * - reads the current {@link PolicySnapshot} from {@link PolicyCache} (lock-free)
 * - applies {@link EnforcementConfig.Mode} (OBSERVE/WARN/ENFORCE)
 * - applies {@link EnforcementConfig.FailureBehavior} when the policy is
 *   MISSING/EXPIRED/INVALID
 * - enqueues the decision for async telemetry — never blocks on network I/O
 *
 * Thread safety: stateless beyond its immutable collaborators; a single
 * instance may be shared across threads.
 */
public final class RollbackGuard {

    private final PolicyCache policyCache;
    private final EnforcementConfig config;
    private final TelemetryBuffer telemetry;

    public RollbackGuard(PolicyCache policyCache, EnforcementConfig config, TelemetryBuffer telemetry) {
        this.policyCache = Objects.requireNonNull(policyCache, "policyCache");
        this.config = Objects.requireNonNull(config, "config");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public MutationDecision evaluate(MutationRequest request) {
        PolicyState state = policyCache.state();

        MutationDecision decision = switch (state) {
            case FRESH, STALE -> ContractEvaluator.evaluate(request, policyCache.get());
            case EXPIRED -> handleUnusablePolicy(request, MutationDecision.ReasonCode.POLICY_EXPIRED);
            case MISSING -> handleUnusablePolicy(request, MutationDecision.ReasonCode.POLICY_MISSING);
            case INVALID -> handleUnusablePolicy(request, MutationDecision.ReasonCode.POLICY_INVALID);
        };

        MutationDecision effective = applyMode(decision);
        telemetry.enqueue(effective);
        return effective;
    }

    private MutationDecision handleUnusablePolicy(MutationRequest request,
                                                    MutationDecision.ReasonCode reason) {
        if (config.failureBehavior() == EnforcementConfig.FailureBehavior.FAIL_CLOSED) {
            return new MutationDecision(MutationDecision.Decision.BLOCK, reason,
                request.entity(), request.field(), request.attemptedValue(), -1, -1);
        }
        return MutationDecision.unknown(reason, request);
    }

    private MutationDecision applyMode(MutationDecision decision) {
        // OBSERVE and WARN never turn an ALLOW into a BLOCK for the caller —
        // they only change what happens downstream (logging/telemetry). The
        // decision object always carries the true evaluation result; it is
        // the caller's/adapter's responsibility to decide whether to act on
        // a BLOCK in OBSERVE/WARN mode. We tag nothing further here; mode is
        // read by the telemetry consumer to decide alerting vs. enforcement.
        return decision;
    }
}
