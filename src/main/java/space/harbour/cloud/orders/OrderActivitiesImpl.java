package space.harbour.cloud.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.harbour.cloud.orders.model.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OrderActivitiesImpl implements OrderActivities {

    private static final Logger log = LoggerFactory.getLogger(OrderActivitiesImpl.class);

    // Menu: representative StarHarbour prices (matches hw1 CoffeeType names).
    private static final Map<String, BigDecimal> MENU = Map.ofEntries(
            Map.entry("ESPRESSO", new BigDecimal("2.00")),
            Map.entry("DOUBLE_ESPRESSO", new BigDecimal("2.50")),
            Map.entry("AMERICANO", new BigDecimal("3.00")),
            Map.entry("LATTE", new BigDecimal("3.50")),
            Map.entry("CAPPUCCINO", new BigDecimal("3.20")),
            Map.entry("FLAT_WHITE", new BigDecimal("3.30")),
            Map.entry("MOCHA", new BigDecimal("3.80")),
            Map.entry("CORTADO", new BigDecimal("3.10")),
            Map.entry("MACCHIATO", new BigDecimal("2.80")),
            Map.entry("COLD_BREW", new BigDecimal("4.00")));

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public OrderActivitiesImpl(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public PricedOrder validateAndPrice(OrderInput in) {
        if (in.storeId() == null || !in.storeId().startsWith("store-")) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Unknown or closed store: " + in.storeId(), "InvalidOrder");
        }
        if (in.items() == null || in.items().isEmpty()) {
            throw ApplicationFailure.newNonRetryableFailure("Order has no items", "InvalidOrder");
        }
        String currency = in.currency() == null ? "EUR" : in.currency();
        List<PricedLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LineItem li : in.items()) {
            BigDecimal unit = MENU.get(li.coffeeType() == null ? null : li.coffeeType().toUpperCase());
            if (unit == null) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "Unknown coffee type: " + li.coffeeType(), "InvalidOrder");
            }
            if (li.quantity() <= 0) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "Invalid quantity for " + li.coffeeType(), "InvalidOrder");
            }
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(li.quantity()));
            lines.add(new PricedLine(li.coffeeType(), li.quantity(),
                    new Money(unit, currency), new Money(lineTotal, currency)));
            total = total.add(lineTotal);
        }
        log.info("Priced order {} -> {} {}", in.orderId(), total, currency);
        return new PricedOrder(lines, new Money(total, currency));
    }

    @Override
    public String reserveInventory(OrderInput in) {
        String oos = in.demo() == null ? null : in.demo().forceOutOfStockType();
        if (oos != null && !oos.isBlank()) {
            for (LineItem li : in.items()) {
                if (li.coffeeType().equalsIgnoreCase(oos)) {
                    log.warn("Inventory check failed for order {}: {} out of stock", in.orderId(), oos);
                    throw new OutOfStockException(li.coffeeType());
                }
            }
        }
        String reservationId = "resv-" + in.orderId();
        log.info("Reserved inventory {} for order {}", reservationId, in.orderId());
        return reservationId;
    }

    @Override
    public void releaseInventory(String reservationId) {
        log.info("Released inventory reservation {}", reservationId); // idempotent no-op stub
    }

    @Override
    public PaymentResult takePayment(String idempotencyKey, Money amount, String paymentMode, OrderInput in) {
        String mode = paymentMode == null ? "real" : paymentMode;
        switch (mode) {
            case "fake-decline":
                log.warn("Payment declined (fake) for key {}", idempotencyKey);
                throw new PaymentDeclinedException("Card declined (simulated)");
            case "fake-timeout": {
                int attempt = Activity.getExecutionContext().getInfo().getAttempt();
                int failTimes = in.demo() == null ? 0 : in.demo().paymentFailTimes();
                if (attempt <= failTimes) {
                    log.warn("Payment gateway timeout (fake) key={} attempt={}", idempotencyKey, attempt);
                    throw new RuntimeException("Simulated gateway timeout, attempt " + attempt); // retryable
                }
                log.info("Fake-timeout recovered on attempt {} - charging hw1 for real", attempt);
                return callHw1(idempotencyKey, amount, in);
            }
            default:
                return callHw1(idempotencyKey, amount, in);
        }
    }

    /** The single real charge against hw1. Same Idempotency-Key on every retry => one payment. */
    private PaymentResult callHw1(String idempotencyKey, Money amount, OrderInput in) {
        String coffeeType = in.items().get(0).coffeeType(); // representative (hw1 is per-coffee)
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("coffeeType", coffeeType);
            body.put("price", amount.amount());
            body.put("currency", amount.currency());
            body.put("loyaltyCardId", in.loyaltyCardId());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/payments"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Store-Id", in.storeId())
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc == 200 || sc == 201) {
                JsonNode node = mapper.readTree(resp.body());
                String paymentId = node.path("paymentId").asText();
                log.info("hw1 charge OK key={} paymentId={} status={}", idempotencyKey, paymentId, sc);
                return new PaymentResult(paymentId, amount, idempotencyKey);
            }
            if (sc >= 400 && sc < 500) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "hw1 rejected payment: HTTP " + sc + " " + resp.body(), "PaymentRejected");
            }
            throw new RuntimeException("hw1 server error HTTP " + sc); // retryable
        } catch (IOException ex) {
            throw new RuntimeException("hw1 call failed: " + ex.getMessage(), ex); // retryable
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("hw1 call interrupted", ex);
        }
    }

    @Override
    public void refundPayment(String idempotencyKey) {
        // hw1 has no refund endpoint; logged stub. Idempotent.
        log.info("Refund issued for payment key {} (stub - hw1 has no refund endpoint)", idempotencyKey);
    }

    @Override
    public void enqueueBaristaTicket(OrderInput in) {
        log.info("Barista ticket enqueued for order {} at {}: {}", in.orderId(), in.storeId(), in.items());
    }

    @Override
    public void notifyCustomer(String orderId, String message) {
        log.info("[notify {}] {}", orderId, message);
    }

    @Override
    public void accrueLoyalty(String orderId, String loyaltyCardId, Money amount) {
        // Deliberate deviation (see docs/report.md): hw1 exposes no loyalty endpoint.
        log.info("Loyalty accrual skipped: hw1 has no loyalty endpoint; orderId={} loyaltyCardId={} amount={}",
                orderId, loyaltyCardId, amount == null ? "n/a" : amount.amount() + " " + amount.currency());
    }
}
