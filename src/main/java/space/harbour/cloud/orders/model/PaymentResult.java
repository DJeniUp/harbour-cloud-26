package space.harbour.cloud.orders.model;

public record PaymentResult(String paymentId, Money amount, String idempotencyKey) {}
