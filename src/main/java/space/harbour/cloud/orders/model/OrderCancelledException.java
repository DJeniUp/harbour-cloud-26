package space.harbour.cloud.orders.model;

/**
 * Internal control-flow signal raised inside the workflow when customerCancelled
 * (or a substitution decline) is observed. drinksMade decides the refund policy:
 *   false -> pre-brew  : full refund + release (saga.compensate)
 *   true  -> post-brew : drinks wasted, no refund, no release
 */
public class OrderCancelledException extends RuntimeException {
    private final boolean drinksMade;

    public OrderCancelledException(boolean drinksMade) {
        super("Order cancelled (drinksMade=" + drinksMade + ")");
        this.drinksMade = drinksMade;
    }

    public boolean drinksMade() {
        return drinksMade;
    }
}
