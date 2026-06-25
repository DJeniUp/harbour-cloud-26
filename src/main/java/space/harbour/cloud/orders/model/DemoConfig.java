package space.harbour.cloud.orders.model;

/**
 * Demo levers passed in via OrderInput so the workflow stays deterministic
 * (durations are workflow arguments, never read from env inside the workflow).
 */
public record DemoConfig(
        long brewSlaSeconds,              // brew-SLA timer
        long substitutionDeadlineSeconds, // substitution-response window
        long pickupExpirySeconds,         // pickup-expiry timer
        int paymentFailTimes,             // fake-timeout: fail this many attempts, then succeed
        String forceOutOfStockType        // F3: coffeeType to report out of stock (null/blank = none)
) {}
