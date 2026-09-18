package com.rollbackshield.demo.order;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The v1 application's view of order storage. v1 was written before
 * PARTIALLY_REFUNDED existed, so its status parser only recognizes four
 * values. Reading anything else throws -- this is the real failure a
 * "successful" compute rollback does not prevent.
 */
public final class OrderStoreV1 {

    public static final Set<String> UNDERSTOOD_STATUSES =
        Set.of("CREATED", "PAID", "CANCELLED", "REFUNDED");

    private final Map<String, Order> orders;

    public OrderStoreV1(Map<String, Order> sharedBackingStore) {
        this.orders = sharedBackingStore;
    }

    /** Simulates v1 reading and acting on an order after a compute rollback. */
    public Order read(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new IllegalStateException("No such order: " + orderId);
        }
        if (!UNDERSTOOD_STATUSES.contains(order.status())) {
            throw new UnknownOrderStatusException(orderId, order.status());
        }
        return order;
    }

    public void write(String orderId, String status) {
        if (!UNDERSTOOD_STATUSES.contains(status)) {
            throw new UnknownOrderStatusException(orderId, status);
        }
        orders.put(orderId, new Order(orderId, status));
    }

    public static final class UnknownOrderStatusException extends RuntimeException {
        public UnknownOrderStatusException(String orderId, String status) {
            super("v1 cannot understand Order[" + orderId + "].status = '" + status
                + "' -- this value did not exist when v1 was built");
        }
    }
}
