package com.rollbackshield.demo.order;

import java.time.Instant;

/**
 * Deliberately stores status as a plain String, not a Java enum, so that a
 * v2-only value written to the store is a real piece of untyped data that a
 * v1 reader can genuinely fail to understand — not something the Java type
 * system would prevent us from writing in the first place.
 */
public final class Order {

    private final String orderId;
    private String status;
    private Instant updatedAt;

    public Order(String orderId, String status) {
        this.orderId = orderId;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String orderId() {
        return orderId;
    }

    public String status() {
        return status;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }
}
