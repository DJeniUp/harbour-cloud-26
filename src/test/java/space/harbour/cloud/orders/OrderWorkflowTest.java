package space.harbour.cloud.orders;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.harbour.cloud.orders.model.DemoConfig;
import space.harbour.cloud.orders.model.LineItem;
import space.harbour.cloud.orders.model.OrderInput;
import space.harbour.cloud.orders.model.OrderState;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1-F6 verified with {@link TestWorkflowEnvironment} time-skipping (no real-time sleeps,
 * no real hw1). Timers (brew-SLA, substitution-deadline, pickup-expiry) and activity-retry
 * backoff are all fast-forwarded by the test service.
 *
 * F6 (crash / replay) is covered two ways:
 *   - test {@code f6_replayIsDeterministic} replays a completed history through
 *     {@link WorkflowReplayer} and asserts no non-determinism;
 *   - the live worker-crash procedure in docs/runbook.md (Ctrl-C the worker mid-order, restart,
 *     finish via signals) exercises real event-history replay on the dev server.
 */
class OrderWorkflowTest {

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;
    private FakeOrderActivities fake;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        fake = new FakeOrderActivities();
        io.temporal.worker.Worker worker = testEnv.newWorker(OrderWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        testEnv.start();
        client = testEnv.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    // --- helpers ---

    private OrderInput order(String orderId, String mode, String forceOutOfStock) {
        DemoConfig demo = new DemoConfig(5, 5, 10, 2, forceOutOfStock); // brewSla, sub, pickup (s), failTimes
        return new OrderInput(orderId, "store-london-01",
                List.of(new LineItem("LATTE", 2), new LineItem("COLD_BREW", 1)),
                "card-saved-001", "card-loyalty-001", mode, "EUR", demo);
    }

    private OrderWorkflow newStub(String orderId) {
        return client.newWorkflowStub(OrderWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId("order-" + orderId)
                .setTaskQueue(OrderWorkflow.TASK_QUEUE)
                .build());
    }

    private OrderState start(OrderWorkflow wf, OrderInput in) {
        WorkflowClient.start(wf::processOrder, in);
        return WorkflowStub.fromTyped(wf).getResult(OrderState.class);
    }

    // --- F1: payment times out N times, then succeeds ---
    @Test
    void f1_timeoutThenSuccess_singleChargeOneKey() {
        String orderId = "f1";
        fake.paymentBehavior = FakeOrderActivities.PaymentBehavior.TIMEOUT_THEN_SUCCEED;
        OrderWorkflow wf = newStub(orderId);

        // drive happy path to completion after payment recovers
        testEnv.registerDelayedCallback(Duration.ofSeconds(10), wf::baristaStarted);
        testEnv.registerDelayedCallback(Duration.ofSeconds(11), wf::allDrinksReady);
        testEnv.registerDelayedCallback(Duration.ofSeconds(12), wf::customerCollected);

        OrderState result = start(wf, order(orderId, "fake-timeout", null));

        assertEquals(OrderState.COMPLETED, result);
        assertTrue(fake.maxPaymentAttempt.get() > 2,
                "expected retries beyond failTimes=2, saw attempt " + fake.maxPaymentAttempt.get());
        assertEquals(1, fake.paymentSuccesses.get(), "exactly one successful charge");
        Set<String> distinctKeys = new HashSet<>(fake.paymentKeysSeen);
        assertEquals(1, distinctKeys.size(), "same idempotency key across all retries");
        assertEquals("order-" + orderId + "-pay", distinctKeys.iterator().next());
    }

    // --- F2: payment permanently declined ---
    @Test
    void f2_declined_failsWithReleaseNoLoyalty() {
        String orderId = "f2";
        fake.paymentBehavior = FakeOrderActivities.PaymentBehavior.DECLINE;
        OrderWorkflow wf = newStub(orderId);

        OrderState result = start(wf, order(orderId, "fake-decline", null));

        assertEquals(OrderState.FAILED, result);
        assertEquals(1, fake.releaseCalls.get(), "inventory released via saga compensation");
        assertEquals(0, fake.loyaltyCalls.get(), "no loyalty on failure");
        assertEquals(0, fake.refundCalls.get(), "charge never succeeded, nothing to refund");
        assertEquals(0, fake.paymentSuccesses.get());
    }

    // --- F3a: out of stock, no response before deadline -> CANCELLED ---
    @Test
    void f3a_outOfStock_noResponse_timesOutToCancelled() {
        String orderId = "f3a";
        OrderWorkflow wf = newStub(orderId);

        // no substitutionResponse; substitution-deadline (5s) fires via time-skip
        OrderState result = start(wf, order(orderId, "real", "COLD_BREW"));

        assertEquals(OrderState.CANCELLED, result);
        assertEquals(0, fake.paymentSuccesses.get(), "payment is after reserve, so no charge");
        assertTrue(fake.paymentKeysSeen.isEmpty(), "takePayment never invoked");
    }

    // --- F3b: out of stock, substitution accepted -> proceeds to completion ---
    @Test
    void f3b_outOfStock_accepted_proceeds() {
        String orderId = "f3b";
        OrderWorkflow wf = newStub(orderId);

        testEnv.registerDelayedCallback(Duration.ofSeconds(1), () -> wf.substitutionResponse(true));
        testEnv.registerDelayedCallback(Duration.ofSeconds(10), wf::baristaStarted);
        testEnv.registerDelayedCallback(Duration.ofSeconds(11), wf::allDrinksReady);
        testEnv.registerDelayedCallback(Duration.ofSeconds(12), wf::customerCollected);

        OrderState result = start(wf, order(orderId, "real", "COLD_BREW"));

        assertEquals(OrderState.COMPLETED, result);
        assertTrue(fake.reserveCalls.get() >= 2, "reserved again after substitution");
        assertEquals(1, fake.paymentSuccesses.get(), "exactly one charge for the substituted order");
    }

    // --- F3c: out of stock, substitution declined -> CANCELLED ---
    @Test
    void f3c_outOfStock_declined_cancelled() {
        String orderId = "f3c";
        OrderWorkflow wf = newStub(orderId);

        testEnv.registerDelayedCallback(Duration.ofSeconds(1), () -> wf.substitutionResponse(false));

        OrderState result = start(wf, order(orderId, "real", "COLD_BREW"));

        assertEquals(OrderState.CANCELLED, result);
        assertTrue(fake.paymentKeysSeen.isEmpty(), "takePayment never invoked");
    }

    // --- F4a: cancel BEFORE brewing -> refund + release ---
    @Test
    void f4a_cancelPreBrew_refundAndRelease() {
        String orderId = "f4a";
        OrderWorkflow wf = newStub(orderId);

        testEnv.registerDelayedCallback(Duration.ofSeconds(1), wf::customerCancelled); // before baristaStarted

        OrderState result = start(wf, order(orderId, "real", null));

        assertEquals(OrderState.CANCELLED, result);
        assertEquals(1, fake.refundCalls.get(), "pre-brew cancel refunds the charge");
        assertEquals(1, fake.releaseCalls.get(), "pre-brew cancel releases inventory");
    }

    // --- F4b: cancel AFTER brewing -> waste, no refund, no release ---
    @Test
    void f4b_cancelPostBrew_wasteNoRefund() {
        String orderId = "f4b";
        OrderWorkflow wf = newStub(orderId);

        // Post-brew boundary = the barista has started. Drive with direct signals + a virtual-time
        // sleep so baristaStarted is processed (workflow in BREWING) BEFORE the cancel arrives,
        // which is then handled as the post-brew waste branch.
        // (NOTE: a registerDelayedCallback that resolves the cancel out of the timed brew-await
        //  trips a TestWorkflowEnvironment time-skipping quirk; main-thread signalling is reliable.)
        WorkflowClient.start(wf::processOrder, order(orderId, "real", null));
        wf.baristaStarted();
        testEnv.sleep(Duration.ofSeconds(1)); // baristaStarted processed -> BREWING (pickup=10s not reached)
        wf.customerCancelled();
        OrderState result = WorkflowStub.fromTyped(wf).getResult(OrderState.class);

        assertEquals(OrderState.CANCELLED, result);
        assertEquals(0, fake.refundCalls.get(), "post-brew cancel is waste, no refund");
        assertEquals(0, fake.releaseCalls.get(), "ingredients consumed, no release");
        assertEquals(1, fake.paymentSuccesses.get());
    }

    // --- F5: never collected -> pickup-expiry -> ABANDONED ---
    @Test
    void f5_neverCollected_abandoned() {
        String orderId = "f5";
        OrderWorkflow wf = newStub(orderId);

        testEnv.registerDelayedCallback(Duration.ofSeconds(1), wf::baristaStarted);
        testEnv.registerDelayedCallback(Duration.ofSeconds(2), wf::allDrinksReady);
        // never send customerCollected; pickup-expiry (10s) fires via time-skip

        OrderState result = start(wf, order(orderId, "real", null));

        assertEquals(OrderState.ABANDONED, result);
        assertEquals(0, fake.refundCalls.get(), "abandoned order is waste, no refund");
        assertEquals(1, fake.paymentSuccesses.get());
    }

    // --- F6: replay determinism (also see live worker-crash runbook) ---
    @Test
    void f6_replayIsDeterministic() {
        String orderId = "f6";
        OrderWorkflow wf = newStub(orderId);

        testEnv.registerDelayedCallback(Duration.ofSeconds(10), wf::baristaStarted);
        testEnv.registerDelayedCallback(Duration.ofSeconds(11), wf::allDrinksReady);
        testEnv.registerDelayedCallback(Duration.ofSeconds(12), wf::customerCollected);

        OrderState result = start(wf, order(orderId, "real", null));
        assertEquals(OrderState.COMPLETED, result);

        // Replay the recorded history against the workflow code: any non-determinism throws.
        io.temporal.common.WorkflowExecutionHistory history = client.fetchHistory("order-" + orderId);
        assertDoesNotThrow(() ->
                WorkflowReplayer.replayWorkflowExecution(history, OrderWorkflowImpl.class));
    }
}
