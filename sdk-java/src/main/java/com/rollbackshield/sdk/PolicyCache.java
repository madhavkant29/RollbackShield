package com.rollbackshield.sdk;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds the current {@link PolicySnapshot} for one contract and refreshes it
 * from a {@link PolicySource} on a background schedule.
 *
 * Reads (get()) are lock-free — a single volatile reference read via
 * {@link AtomicReference}. Refresh failures never clear the cache: the last
 * known-good snapshot is retained and classified STALE/EXPIRED by age, so a
 * transient control-plane outage degrades gracefully instead of blanking
 * enforcement (see docs/features/POLICY_DISTRIBUTION or ADR-003).
 */
public final class PolicyCache {

    private static final Logger log = Logger.getLogger(PolicyCache.class.getName());

    private final String contractId;
    private final PolicySource source;
    private final EnforcementConfig config;
    private final AtomicReference<PolicySnapshot> current = new AtomicReference<>();
    private volatile Instant lastRefreshAttempt;
    private volatile boolean lastRefreshFailed;

    public PolicyCache(String contractId, PolicySource source, EnforcementConfig config) {
        this.contractId = contractId;
        this.source = source;
        this.config = config;
    }

    /** Synchronous first load — call once at startup before serving traffic. */
    public void initialize() {
        refresh();
    }

    /** Call from a scheduled executor; never on the mutation hot path. */
    public void refresh() {
        lastRefreshAttempt = Instant.now();
        try {
            PolicySnapshot fetched = source.fetch(contractId);
            PolicySnapshot existing = current.get();
            if (existing == null || fetched.policyVersion() > existing.policyVersion()) {
                current.set(fetched);
            }
            lastRefreshFailed = false;
        } catch (PolicySource.PolicyFetchException e) {
            lastRefreshFailed = true;
            log.log(Level.WARNING, "Policy refresh failed for contract " + contractId
                + "; retaining last-known-good snapshot", e);
        }
    }

    /** Lock-free hot-path read. */
    public PolicySnapshot get() {
        return current.get();
    }

    public PolicyState state() {
        PolicySnapshot snapshot = current.get();
        if (snapshot == null) {
            return PolicyState.MISSING;
        }
        Duration age = Duration.between(snapshot.fetchedAt(), Instant.now());
        if (age.compareTo(config.expireAfter()) > 0) {
            return PolicyState.EXPIRED;
        }
        if (age.compareTo(config.staleAfter()) > 0) {
            return PolicyState.STALE;
        }
        return PolicyState.FRESH;
    }

    public boolean lastRefreshFailed() {
        return lastRefreshFailed;
    }
}
