package space.harbour.cloud.orders.model;

import java.util.List;

public record OrderInput(
        String orderId,
        String storeId,
        List<LineItem> items,
        String savedCardId,
        String loyaltyCardId,
        String paymentMode,   // real | fake-timeout | fake-decline
        String currency,      // ISO-4217, e.g. EUR
        DemoConfig demo
) {}
