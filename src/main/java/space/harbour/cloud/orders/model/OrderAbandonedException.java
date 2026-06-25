package space.harbour.cloud.orders.model;

/** Internal control-flow signal: pickup-expiry timer beat customerCollected (F5). */
public class OrderAbandonedException extends RuntimeException {
    public OrderAbandonedException() {
        super("Order abandoned - not collected before pickup-expiry");
    }
}
