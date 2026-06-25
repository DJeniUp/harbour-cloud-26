package space.harbour.cloud.orders.model;

public record PricedLine(String coffeeType, int quantity, Money unitPrice, Money lineTotal) {}
