package space.harbour.cloud.orders.model;

/** Thrown by reserveInventory. Configured non-retryable via ActivityOptions.doNotRetry. */
public class OutOfStockException extends RuntimeException {
    public OutOfStockException(String coffeeType) {
        super("Out of stock: " + coffeeType);
    }
}
