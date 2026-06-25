package space.harbour.cloud.orders.model;

/** The single source of truth for an order's lifecycle. */
public enum OrderState {
    PLACED, PRICED, INVENTORY_RESERVED, PAID, BREWING, READY, COLLECTED, COMPLETED,
    // terminal failure states
    FAILED, CANCELLED, ABANDONED
}
