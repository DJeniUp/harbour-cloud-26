package space.harbour.cloud.orders;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Worker {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private Worker() {}

    public static void main(String[] args) {
        String target = env("TEMPORAL_TARGET", "127.0.0.1:7233");
        String paymentsBaseUrl = env("PAYMENTS_BASE_URL", "http://localhost:8080");

        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        io.temporal.worker.Worker worker = factory.newWorker(OrderWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(new OrderActivitiesImpl(paymentsBaseUrl));

        factory.start();
        log.info("Worker started. taskQueue={} temporal={} paymentsBaseUrl={}",
                OrderWorkflow.TASK_QUEUE, target, paymentsBaseUrl);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
