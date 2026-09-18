package com.rollbackshield.demo.run;

import com.rollbackshield.demo.order.Order;
import com.rollbackshield.demo.order.OrderStoreV1;
import com.rollbackshield.demo.order.OrderStoreV2;
import com.rollbackshield.demo.workfence.AsyncJob;
import com.rollbackshield.demo.workfence.AsyncWorker;
import com.rollbackshield.demo.workfence.EpochRegistry;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Demonstrates the failure scenario from §2 with no RollbackShield
 * involvement at all. Every step below is real code executing against real
 * in-memory state -- there is no scripted/fake output.
 */
public final class UnprotectedDemo {

    public static void main(String[] args) {
        Map<String, Order> backingStore = new HashMap<>();
        OrderStoreV2 v2Store = new OrderStoreV2(backingStore);
        OrderStoreV1 v1Store = new OrderStoreV1(backingStore);
        EpochRegistry epochRegistry = new EpochRegistry(); // never invalidated in this demo
        AsyncWorker worker = new AsyncWorker(epochRegistry);

        String orderId = "order-unprotected-1";

        System.out.println("[1] deploy v2, write Order.status = PARTIALLY_REFUNDED (no guard)");
        v2Store.write(orderId, "PARTIALLY_REFUNDED");
        System.out.println("    wrote: " + v2Store.read(orderId).status());

        System.out.println("[2] v2 enqueues async work tagged with release epoch 2 (unfenced)");
        AsyncJob job = new AsyncJob("job-1", "release-v2", 2L, "ISSUE_PARTIAL_REFUND_WEBHOOK",
            Instant.now(), orderId);

        System.out.println("[3] production bug discovered; rolling application compute back to v1");
        System.out.println("    (application compute now v1 -- no state migration occurred)");

        System.out.println("[4] v1 attempts to read the order after rollback:");
        try {
            v1Store.read(orderId);
            System.out.println("    UNEXPECTED: v1 read succeeded");
        } catch (OrderStoreV1.UnknownOrderStatusException e) {
            System.out.println("    v1 FAILED: " + e.getMessage());
        }

        System.out.println("[5] the old v2 queued work executes anyway (no fencing):");
        AsyncWorker.JobOutcome outcome = worker.deliver(job);
        System.out.println("    job outcome: " + outcome
            + " | irreversible side effects performed: " + worker.irreversibleSideEffectsPerformed());

        System.out.println();
        System.out.println("RESULT: compute rolled back successfully, but production is broken");
        System.out.println("(v1 cannot read its own order, and v2's queued work fired anyway).");
    }
}
