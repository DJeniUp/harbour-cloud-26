package space.harbour.cloud.orders;

import io.temporal.activity.Activity;
import space.harbour.cloud.orders.model.LineItem;
import space.harbour.cloud.orders.model.Money;
import space.harbour.cloud.orders.model.OrderInput;
import space.harbour.cloud.orders.model.OutOfStockException;
import space.harbour.cloud.orders.model.PaymentDeclinedException;
import space.harbour.cloud.orders.model.PaymentResult;
import space.harbour.cloud.orders.model.PricedLine;
import space.harbour.cloud.orders.model.PricedOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory fake of {@link OrderActivities} for unit tests. Records every invocation so the
 * tests can assert saga compensation, loyalty, and the single-idempotency-key guarantee.
 * Never touches the real hw1 API.
 */
class FakeOrderActivities implements OrderActivities {

    enum PaymentBehavior { SUCCEED, DECLINE, TIMEOUT_THEN_SUCCEED }

    volatile PaymentBehavior paymentBehavior = PaymentBehavior.SUCCEED;

    final List<String> paymentKeysSeen = new CopyOnWriteArrayList<>();
    final AtomicInteger maxPaymentAttempt = new AtomicInteger(0);
    final AtomicInteger paymentSuccesses = new AtomicInteger(0);
    final AtomicInteger reserveCalls = new AtomicInteger(0);
    final AtomicInteger releaseCalls = new AtomicInteger(0);
    final AtomicInteger refundCalls = new AtomicInteger(0);
    final AtomicInteger loyaltyCalls = new AtomicInteger(0);

    @Override
    public PricedOrder validateAndPrice(OrderInput in) {
        String currency = in.currency() == null ? "EUR" : in.currency();
        BigDecimal unit = new BigDecimal("3.50");
        List<PricedLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LineItem li : in.items()) {
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(li.quantity()));
            lines.add(new PricedLine(li.coffeeType(), li.quantity(),
                    new Money(unit, currency), new Money(lineTotal, currency)));
            total = total.add(lineTotal);
        }
        return new PricedOrder(lines, new Money(total, currency));
    }

    @Override
    public String reserveInventory(OrderInput in) {
        reserveCalls.incrementAndGet();
        String oos = in.demo() == null ? null : in.demo().forceOutOfStockType();
        if (oos != null && !oos.isBlank()) {
            for (LineItem li : in.items()) {
                if (li.coffeeType().equalsIgnoreCase(oos)) {
                    throw new OutOfStockException(li.coffeeType()); // non-retryable via doNotRetry
                }
            }
        }
        return "resv-" + in.orderId();
    }

    @Override
    public void releaseInventory(String reservationId) {
        releaseCalls.incrementAndGet();
    }

    @Override
    public PaymentResult takePayment(String idempotencyKey, Money amount, String paymentMode, OrderInput in) {
        paymentKeysSeen.add(idempotencyKey);
        int attempt = Activity.getExecutionContext().getInfo().getAttempt();
        maxPaymentAttempt.accumulateAndGet(attempt, Math::max);

        switch (paymentBehavior) {
            case DECLINE -> throw new PaymentDeclinedException("Card declined (test)");
            case TIMEOUT_THEN_SUCCEED -> {
                int failTimes = in.demo() == null ? 0 : in.demo().paymentFailTimes();
                if (attempt <= failTimes) {
                    throw new RuntimeException("Simulated gateway timeout, attempt " + attempt); // retryable
                }
            }
            default -> { /* SUCCEED */ }
        }
        paymentSuccesses.incrementAndGet();
        return new PaymentResult("pay-" + idempotencyKey, amount, idempotencyKey);
    }

    @Override
    public void refundPayment(String idempotencyKey) {
        refundCalls.incrementAndGet();
    }

    @Override
    public void enqueueBaristaTicket(OrderInput in) { /* no-op */ }

    @Override
    public void notifyCustomer(String orderId, String message) { /* no-op */ }

    @Override
    public void accrueLoyalty(String orderId, String loyaltyCardId, Money amount) {
        loyaltyCalls.incrementAndGet();
    }
}
