package space.harbour.cloud.orders.model;

import java.util.List;

public record PricedOrder(List<PricedLine> lines, Money total) {}
