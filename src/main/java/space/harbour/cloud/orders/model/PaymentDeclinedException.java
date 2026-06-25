package space.harbour.cloud.orders.model;

/** Thrown by takePayment on a definitive decline. Non-retryable via ActivityOptions.doNotRetry. */
public class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String message) {
        super(message);
    }
}
