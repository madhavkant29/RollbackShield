package com.rollbackshield.demo.order;

import java.util.Map;

/**
 * The v2 application's view of order storage. Unlike v1, v2 has no opinion
 * about which statuses are "safe" — it will happily persist
 * PARTIALLY_REFUNDED. Whether that write is allowed to happen at all is
 * RollbackGuard's job, applied by the caller *before* reaching this class
 * (see ProtectedDemo) — this store itself performs no validation, exactly
 * like a real v2 service that doesn't yet know it needs to.
 */
public final class OrderStoreV2 {

    private final Map<String, Order> orders;

    public OrderStoreV2(Map<String, Order> sharedBackingStore) {
        this.orders = sharedBackingStore;
    }

    public void write(String orderId, String status) {
        orders.put(orderId, new Order(orderId, status));
    }

    public Order read(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new IllegalStateException("No such order: " + orderId);
        }
        return order;
    }
}
