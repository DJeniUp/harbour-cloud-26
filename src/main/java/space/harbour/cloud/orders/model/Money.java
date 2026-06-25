package space.harbour.cloud.orders.model;

import java.math.BigDecimal;

public record Money(BigDecimal amount, String currency) {}
