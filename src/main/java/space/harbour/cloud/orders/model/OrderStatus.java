package space.harbour.cloud.orders.model;

import java.util.Map;

/** Query DTO: current state + transition timestamps for the Web UI / demo. */
public record OrderStatus(
        String orderId,
        OrderState state,
        Map<String, Long> timestamps, // state name -> epoch millis (Workflow.currentTimeMillis)
        Money total,
        String paymentId,
        String lastMessage
) {}
