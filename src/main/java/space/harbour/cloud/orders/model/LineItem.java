package space.harbour.cloud.orders.model;

/** One line of an order, e.g. 2x LATTE. coffeeType matches the hw1 CoffeeType names. */
public record LineItem(String coffeeType, int quantity) {}
