package space.harbour.cloud.orders;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import space.harbour.cloud.orders.model.OrderInput;
import space.harbour.cloud.orders.model.OrderState;
import space.harbour.cloud.orders.model.OrderStatus;

@WorkflowInterface
public interface OrderWorkflow {

    /** Task queue shared by the worker and the starter. */
    String TASK_QUEUE = "orders-tq";

    @WorkflowMethod
    OrderState processOrder(OrderInput input);

    // --- signals: external events arriving later ---
    @SignalMethod void baristaStarted();
    @SignalMethod void drinkReady(int lineIndex);
    @SignalMethod void allDrinksReady();
    @SignalMethod void customerCollected();
    @SignalMethod void customerCancelled();
    @SignalMethod void substitutionResponse(boolean accepted);

    // --- query: current state for the UI/demo ---
    @QueryMethod OrderStatus getStatus();
}
