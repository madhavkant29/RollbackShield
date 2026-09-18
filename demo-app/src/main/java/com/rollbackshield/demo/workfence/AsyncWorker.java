package com.rollbackshield.demo.workfence;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes queued asynchronous work. Models SQS's at-least-once delivery
 * honestly: {@link #deliver} may be called more than once for the same
 * job, and the worker must still perform the irreversible side effect at
 * most once.
 */
public final class AsyncWorker {

    public enum JobOutcome { EXECUTED, CANCELLED }

    private final EpochRegistry epochRegistry;
    private final ConcurrentHashMap<String, JobOutcome> redeemed = new ConcurrentHashMap<>();

    /** Counts real irreversible side effects performed — e.g. refund webhooks fired. */
    private final AtomicInteger irreversibleSideEffectsPerformed = new AtomicInteger(0);

    public AsyncWorker(EpochRegistry epochRegistry) {
        this.epochRegistry = epochRegistry;
    }

    /**
     * Delivers (or re-delivers) a job to the worker. Redemption is decided
     * atomically per jobId: the first delivery that reaches this method
     * evaluates epoch validity and performs the side effect if valid; every
     * subsequent delivery of the same jobId — no matter how many, no matter
     * whether the epoch changed in between — observes the outcome the first
     * delivery already committed to.
     */
    public JobOutcome deliver(AsyncJob job) {
        return redeemed.computeIfAbsent(job.jobId(), id -> {
            if (!epochRegistry.isValid(job.releaseEpoch())) {
                return JobOutcome.CANCELLED;
            }
            performIrreversibleSideEffect(job);
            return JobOutcome.EXECUTED;
        });
    }

    private void performIrreversibleSideEffect(AsyncJob job) {
        // Stand-in for a real irreversible effect (e.g. firing a partner
        // refund webhook). What matters for the demo is that this method is
        // provably called at most once per job across any number of
        // deliveries, and never at all once the job's epoch is invalidated.
        irreversibleSideEffectsPerformed.incrementAndGet();
    }

    public int irreversibleSideEffectsPerformed() {
        return irreversibleSideEffectsPerformed.get();
    }
}
