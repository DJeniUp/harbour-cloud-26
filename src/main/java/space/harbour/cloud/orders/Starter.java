package space.harbour.cloud.orders;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import space.harbour.cloud.orders.model.DemoConfig;
import space.harbour.cloud.orders.model.LineItem;
import space.harbour.cloud.orders.model.OrderInput;
import space.harbour.cloud.orders.model.OrderStatus;

import java.util.List;

/**
 * Usage (via Gradle):
 *   ./gradlew runStarter -Pargs="start <scenario> [orderId]"
 *   ./gradlew runStarter -Pargs="signal <workflowId> <signalName> [value]"
 *   ./gradlew runStarter -Pargs="query <workflowId>"
 *
 * scenario in {happy, F1, F2, F3, F4, F5, F6}.
 */
public final class Starter {

    private Starter() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("commands: start <scenario> [orderId] | signal <wfId> <name> [value] | query <wfId>");
            return;
        }
        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(env("TEMPORAL_TARGET", "127.0.0.1:7233")).build());
        WorkflowClient client = WorkflowClient.newInstance(service);

        switch (args[0]) {
            case "start"  -> start(client, args);
            case "signal" -> signal(client, args);
            case "query"  -> query(client, args);
            default -> System.out.println("unknown command: " + args[0]);
        }
        System.exit(0);
    }

    private static void start(WorkflowClient client, String[] args) {
        String scenario = args.length > 1 ? args[1].toUpperCase() : "HAPPY";
        String orderId = args.length > 2 ? args[2] : "ord-" + System.currentTimeMillis();

        String paymentMode = "real";
        String forceOutOfStock = null;
        switch (scenario) {
            case "F1" -> paymentMode = "fake-timeout";
            case "F2" -> paymentMode = "fake-decline";
            case "F3" -> forceOutOfStock = "COLD_BREW";
            default -> { /* HAPPY, F4, F5, F6 use real payment + signals/timers */ }
        }

        DemoConfig demo = new DemoConfig(
                envLong("DEMO_BREW_SLA_SECONDS", 10),
                envLong("DEMO_SUBSTITUTION_SECONDS", 20),
                envLong("DEMO_PICKUP_SECONDS", 30),
                (int) envLong("DEMO_PAYMENT_FAIL_TIMES", 3),
                forceOutOfStock);

        OrderInput input = new OrderInput(
                orderId, "store-london-01",
                List.of(new LineItem("LATTE", 2), new LineItem("COLD_BREW", 1)),
                "card-saved-001", "card-loyalty-001",
                paymentMode, "EUR", demo);

        String workflowId = "order-" + orderId;
        OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(OrderWorkflow.TASK_QUEUE)
                .build());
        WorkflowClient.start(wf::processOrder, input);

        System.out.printf("Started scenario=%s workflowId=%s paymentMode=%s demo=%s%n",
                scenario, workflowId, paymentMode, demo);
    }

    private static void signal(WorkflowClient client, String[] args) {
        String workflowId = args[1];
        String name = args[2];
        String value = args.length > 3 ? args[3] : null;
        OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class, workflowId);
        switch (name) {
            case "baristaStarted"       -> wf.baristaStarted();
            case "allDrinksReady"       -> wf.allDrinksReady();
            case "drinkReady"           -> wf.drinkReady(Integer.parseInt(value));
            case "customerCollected"    -> wf.customerCollected();
            case "customerCancelled"    -> wf.customerCancelled();
            case "substitutionResponse" -> wf.substitutionResponse(Boolean.parseBoolean(value));
            default -> { System.out.println("unknown signal: " + name); return; }
        }
        System.out.printf("Sent signal %s%s to %s%n", name, value == null ? "" : "(" + value + ")", workflowId);
    }

    private static void query(WorkflowClient client, String[] args) {
        OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class, args[1]);
        OrderStatus s = wf.getStatus();
        System.out.printf("state=%s total=%s paymentId=%s lastMessage=%s%n timestamps=%s%n",
                s.state(), s.total(), s.paymentId(), s.lastMessage(), s.timestamps());
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? d : v;
    }

    private static long envLong(String k, long d) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? d : Long.parseLong(v);
    }
}
