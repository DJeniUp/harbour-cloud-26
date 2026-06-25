package space.harbour.cloud.orders;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import space.harbour.cloud.orders.model.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OrderWorkflowImpl implements OrderWorkflow {

    private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);
    private static final String SUBSTITUTE = "AMERICANO"; // deterministic substitution offer (F3)

    private final ActivityOptions activityOptions = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(10))
                    .setMaximumAttempts(10)
                    .setDoNotRetry(
                            OutOfStockException.class.getName(),
                            PaymentDeclinedException.class.getName())
                    .build())
            .build();

    private final OrderActivities activities =
            Workflow.newActivityStub(OrderActivities.class, activityOptions);

    // --- workflow state (single source of truth) ---
    private OrderInput input;
    private OrderState state = OrderState.PLACED;
    private final Map<String, Long> timestamps = new LinkedHashMap<>();
    private Money total;
    private String paymentId;
    private String lastMessage;
    private String paymentKey; // generated ONCE, stable across retries + replay (F1 + F6)

    // --- signal flags ---
    private boolean baristaStarted;
    private boolean drinksReady;
    private boolean collected;
    private boolean cancelled;
    private Boolean substitutionResponse; // null until customer answers
    private final Set<Integer> readyLines = new HashSet<>();

    @Override
    public OrderState processOrder(OrderInput in) {
        this.input = in;
        this.paymentKey = "order-" + in.orderId() + "-pay"; // generated once, deterministic
        transition(OrderState.PLACED);

        Saga saga = new Saga(new Saga.Options.Builder().build());
        try {
            // 1. price
            PricedOrder priced = activities.validateAndPrice(in);
            this.total = priced.total();
            transition(OrderState.PRICED);

            // 2. reserve inventory (handles F3 out-of-stock + substitution)
            reserveWithSubstitution(saga);

            // 3. take payment (F1 retries; F2 declined -> non-retryable)
            PaymentResult pay = activities.takePayment(paymentKey, priced.total(), in.paymentMode(), in);
            saga.addCompensation(() -> activities.refundPayment(paymentKey));
            this.paymentId = pay.paymentId();
            transition(OrderState.PAID);

            // 4. queue for the barista
            activities.enqueueBaristaTicket(in);
            notify("Order paid and queued. Total " + money(total));

            // 5. wait for the barista to start (or a pre-brew cancellation).
            // Pre-brew only applies if the barista has NOT started; if both signals are present,
            // baristaStarted wins and any cancellation is handled as post-brew (waste) below.
            Workflow.await(() -> baristaStarted || cancelled);
            if (cancelled && !baristaStarted) throw new OrderCancelledException(false); // pre-brew
            transition(OrderState.BREWING);

            // 6. brew with SLA escalation (timer must NOT end the order)
            boolean ready = Workflow.await(Duration.ofSeconds(in.demo().brewSlaSeconds()),
                    () -> drinksReady || cancelled);
            if (cancelled) throw new OrderCancelledException(true); // ingredients committed -> waste
            if (!ready && !drinksReady) {
                notify("Brew-SLA breached - escalating to manager (order stays open)");
                Workflow.await(() -> drinksReady || cancelled); // keep waiting, no deadline
                if (cancelled) throw new OrderCancelledException(true);
            }
            transition(OrderState.READY);
            notify("Your order is ready for pickup");

            // 7. pickup-expiry race (F5)
            boolean wasCollected = Workflow.await(Duration.ofSeconds(in.demo().pickupExpirySeconds()),
                    () -> collected || cancelled);
            if (cancelled) throw new OrderCancelledException(true); // post-brew cancel = waste
            if (!wasCollected) throw new OrderAbandonedException();  // timer won
            transition(OrderState.COLLECTED);

            // 8. loyalty only on the success path -> never needs reversal
            activities.accrueLoyalty(in.orderId(), in.loyaltyCardId(), total);
            transition(OrderState.COMPLETED);
            return state;

        } catch (OrderCancelledException e) { // F4 (and substitution-decline F3)
            if (!e.drinksMade()) {
                saga.compensate(); // conditional: release inventory + refund payment if either ran
                notify("Order cancelled - inventory released and payment refunded");
            } else {
                notify("Order cancelled after brewing - drinks wasted, no refund (policy)");
            }
            transition(OrderState.CANCELLED);
            return state;

        } catch (OrderAbandonedException e) { // F5
            notify("Order abandoned - not collected in time, drinks discarded, no refund (policy)");
            transition(OrderState.ABANDONED);
            return state;

        } catch (ActivityFailure e) { // F2 decline, validation errors, terminal reservation failure
            saga.compensate(); // release inventory if reserved (payment never succeeded here)
            notify("Order failed: " + reason(e));
            transition(OrderState.FAILED);
            return state;
        }
    }

    /** Reserve inventory; on out-of-stock, offer a substitution within a deadline (F3). */
    private void reserveWithSubstitution(Saga saga) {
        try {
            String reservationId = activities.reserveInventory(input);
            saga.addCompensation(() -> activities.releaseInventory(reservationId));
            transition(OrderState.INVENTORY_RESERVED);
        } catch (ActivityFailure e) {
            if (!isType(e, OutOfStockException.class)) throw e; // other failures -> generic catch -> FAILED

            String oos = input.demo().forceOutOfStockType();
            notify("Out of stock: " + oos + ". Substitute with " + SUBSTITUTE + "? Reply within the window.");

            boolean responded = Workflow.await(Duration.ofSeconds(input.demo().substitutionDeadlineSeconds()),
                    () -> substitutionResponse != null || cancelled);
            if (cancelled) throw new OrderCancelledException(false);

            if (!responded || Boolean.FALSE.equals(substitutionResponse)) {
                notify(responded ? "Substitution declined - cancelling order"
                                 : "Substitution window expired - cancelling order");
                throw new OrderCancelledException(false); // saga.compensate handles refund-if-paid + release
            }

            // accepted -> rebuild the order with the substitute and re-reserve
            this.input = withSubstitute(input, oos, SUBSTITUTE);
            notify("Substitution accepted: " + oos + " -> " + SUBSTITUTE);
            String reservationId = activities.reserveInventory(input);
            saga.addCompensation(() -> activities.releaseInventory(reservationId));
            transition(OrderState.INVENTORY_RESERVED);
        }
    }

    // --- signals ---
    @Override public void baristaStarted() { this.baristaStarted = true; }
    @Override public void allDrinksReady() { this.drinksReady = true; }
    @Override public void drinkReady(int lineIndex) {
        readyLines.add(lineIndex);
        if (input != null && readyLines.size() >= input.items().size()) this.drinksReady = true;
    }
    @Override public void customerCollected() { this.collected = true; }
    @Override public void customerCancelled() { this.cancelled = true; }
    @Override public void substitutionResponse(boolean accepted) { this.substitutionResponse = accepted; }

    // --- query ---
    @Override
    public OrderStatus getStatus() {
        return new OrderStatus(
                input != null ? input.orderId() : null,
                state,
                new LinkedHashMap<>(timestamps),
                total,
                paymentId,
                lastMessage);
    }

    // --- helpers ---
    private void transition(OrderState s) {
        this.state = s;
        this.timestamps.put(s.name(), Workflow.currentTimeMillis());
        log.info("Order {} -> {}", input != null ? input.orderId() : "?", s);
    }

    private void notify(String message) {
        this.lastMessage = message;
        activities.notifyCustomer(input.orderId(), message);
    }

    private static boolean isType(ActivityFailure e, Class<?> type) {
        return e.getCause() instanceof ApplicationFailure af && type.getName().equals(af.getType());
    }

    private static String reason(ActivityFailure e) {
        return (e.getCause() instanceof ApplicationFailure af) ? af.getOriginalMessage() : e.getMessage();
    }

    private static String money(Money m) {
        return m == null ? "n/a" : m.amount() + " " + m.currency();
    }

    private static OrderInput withSubstitute(OrderInput in, String from, String to) {
        List<LineItem> items = new ArrayList<>();
        for (LineItem li : in.items()) {
            items.add(li.coffeeType().equalsIgnoreCase(from) ? new LineItem(to, li.quantity()) : li);
        }
        DemoConfig d = in.demo();
        DemoConfig demo = new DemoConfig(d.brewSlaSeconds(), d.substitutionDeadlineSeconds(),
                d.pickupExpirySeconds(), d.paymentFailTimes(), null); // clear OOS lever
        return new OrderInput(in.orderId(), in.storeId(), items, in.savedCardId(),
                in.loyaltyCardId(), in.paymentMode(), in.currency(), demo);
    }
}
