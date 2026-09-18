package com.rollbackshield.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded queue of {@link MutationDecision}s awaiting async delivery to the
 * control plane. {@link #enqueue} never blocks: on a full buffer it drops the
 * event and counts it, rather than applying backpressure to the caller's
 * mutation path. A scheduled task should call {@link #drainBatch} on an
 * interval and ship the results off-thread.
 */
public final class TelemetryBuffer {

    private final BlockingQueue<MutationDecision> queue;
    private final LongAdder dropped = new LongAdder();

    public TelemetryBuffer(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public void enqueue(MutationDecision decision) {
        boolean accepted = queue.offer(decision);
        if (!accepted) {
            dropped.increment();
        }
    }

    public List<MutationDecision> drainBatch(int maxItems) {
        List<MutationDecision> batch = new ArrayList<>(Math.min(maxItems, queue.size()));
        queue.drainTo(batch, maxItems);
        return batch;
    }

    public long droppedCount() {
        return dropped.sum();
    }

    public int size() {
        return queue.size();
    }
}
