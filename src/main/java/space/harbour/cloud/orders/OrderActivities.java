package space.harbour.cloud.orders;

import io.temporal.activity.ActivityInterface;
import space.harbour.cloud.orders.model.Money;
import space.harbour.cloud.orders.model.OrderInput;
import space.harbour.cloud.orders.model.PaymentResult;
import space.harbour.cloud.orders.model.PricedOrder;

/** All side effects live here; every method is idempotent. */
@ActivityInterface
public interface OrderActivities {

    PricedOrder validateAndPrice(OrderInput in);

    String reserveInventory(OrderInput in);

    void releaseInventory(String reservationId); // compensation for reserveInventory

    /**
     * Charges the order total against hw1 (real mode). idempotencyKey is generated once
     * in the workflow and passed unchanged on every retry -> exactly one charge.
     */
    PaymentResult takePayment(String idempotencyKey, Money amount, String paymentMode, OrderInput in);

    void refundPayment(String idempotencyKey); // compensation for takePayment

    void enqueueBaristaTicket(OrderInput in);

    void notifyCustomer(String orderId, String message);

    void accrueLoyalty(String orderId, String loyaltyCardId, Money amount);
}
