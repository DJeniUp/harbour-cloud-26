# OrderWorkflow — design report

## 1. Engine choice: Temporal

| Option | Verdict |
|---|---|
| **Temporal (chosen)** | Durable, replay-based execution; first-class signals/queries/timers; saga support; self-hostable; great local dev (`temporal server start-dev`) and Web UI. Java SDK fits the existing JVM stack. |
| Cadence | Temporal's predecessor; same model but smaller ecosystem and slower-moving SDKs. No reason to prefer it for a greenfield workflow. |
| AWS Step Functions | Managed and cheap to start, but ASL is declarative JSON, long human-in-the-loop waits and rich branching are awkward, local dev is weaker, and it couples us to AWS. Our order is a long-lived, signal-driven saga — Temporal's code-first model expresses it far more naturally. |

A 30+ minute, human-in-the-loop, exactly-once-charge order is exactly Temporal's sweet spot:
the workflow **is** the order's state and survives worker restarts via event-history replay.

## 2. Determinism

The workflow body must produce identical commands on replay. Rules followed:
- No `System.currentTimeMillis()` / `new Date()` — use `Workflow.currentTimeMillis()`.
- No `Random` — not needed; the only "generated" value (the payment idempotency key) is
  derived deterministically from `orderId`. `Workflow.newRandom()` is the tool if randomness
  is ever required.
- No direct I/O — every side effect (HTTP to hw1, logging of business events, notifications)
  is in an **activity**.
- No nondeterministic iteration — `readyLines` is only probed by `size()`/`add()`; order
  substitution iterates a `List` in order.
- Timer durations come in via `OrderInput.DemoConfig` (the **Starter** reads env vars, not the
  workflow), so `Workflow.await(Duration, …)` is deterministic and replay-safe.
- Logging uses `Workflow.getLogger`, which suppresses duplicate lines during replay.

## 3. Idempotency (covers F1 + F6)

`paymentKey = "order-{orderId}-pay"` is computed **once** at the top of `processOrder` and
stored in workflow state. Every `takePayment` retry — and every replay after a worker crash —
passes the same key, and hw1 dedupes on `Store-Id × Idempotency-Key`. Result: **exactly one
charge**, regardless of retries or restarts.

## 4. Compensation (saga) & cancellation policy

Compensations are registered with `io.temporal.workflow.Saga` **as each forward step
succeeds** (release inventory after reserve; refund after payment). On a terminal failure we
call `saga.compensate()`, which runs them in reverse. The F3 substitution-decline path also
routes through `saga.compensate()` (conditional refund + release) so it stays correct even if
a charge exists or steps are reordered.

**Cancellation refund policy — the pre- vs post-brew boundary is `BREWING`:**
- **Pre-BREWING** (`PAID` or earlier): ingredients not yet committed → **full refund +
  inventory release**, state `CANCELLED`.
- **BREWING or later** (drinks being made / made): ingredients consumed → **drinks wasted, no
  refund, no release**, state `CANCELLED`.

**Abandonment (F5):** pickup-expiry timer beats `customerCollected` → drinks already made →
**waste, no refund**, state `ABANDONED`.

**Brew-SLA (timer):** breaching the SLA escalates (notify manager) but **does not end the
order** — it keeps waiting for the drinks.

## 5. Deliberate deviations from the spec

1. **Loyalty is a logged stub.** hw1 exposes no loyalty endpoint (`loyaltyCardId` is only a
   field on a payment), so `accrueLoyalty` logs
   `"Loyalty accrual skipped: hw1 has no loyalty endpoint; ..."` and returns. `takePayment`
   is the only activity that really calls hw1. Loyalty is accrued only on the success path, so
   no cancellation ever needs to reverse it.
2. **Single-charge model.** hw1 is one-coffee-per-POST; an order is multi-line. `takePayment`
   posts **one** hw1 payment for the **order total** with `Idempotency-Key=order-{orderId}-pay`,
   `Store-Id=storeId`, and the first line's coffeeType as a representative. This preserves the
   verifiable "exactly one charge" guarantee.
3. **Refund is a logged stub** (hw1 has no refund endpoint).
4. **Store-open check** is a simple `storeId` prefix check.

## 6. Failure-scenario mapping

| Scenario | Mechanism | Terminal state |
|---|---|---|
| F1 timeout→success | `RetryOptions` backoff; `fake-timeout` fails N attempts (via `getInfo().getAttempt()`) then charges hw1 once with the stable key | COMPLETED, 1 charge |
| F2 declined | non-retryable `PaymentDeclinedException` → `saga.compensate()` (release) | FAILED, 0 charge |
| F3 out of stock | non-retryable `OutOfStockException` → notify + `await(substitution-deadline)`; accept→substitute, decline/expiry→cancel | COMPLETED or CANCELLED |
| F4 cancel | `customerCancelled` signal; pre-brew refund+release, post-brew waste | CANCELLED |
| F5 not collected | pickup-expiry timer beats `customerCollected` | ABANDONED |
| F6 worker crash | Temporal event-history replay + stable idempotency key | resumes, no double charge |

**F6 verification:** verified via **deterministic `WorkflowReplayer` replay** (test
`f6_replayIsDeterministic` replays a completed event history against the workflow code and
asserts no non-determinism). The **live worker-crash procedure is documented in
`docs/runbook.md`** (start → advance to BREWING → Ctrl-C the worker → restart → finish via
signals → confirm one charge); it has **not** been executed as a live crash here.

## 7. Java 25 note

The project runs on Java 25. Temporal's `workflowcheck`/ASM bytecode analysis must support the
class-file version — we pin `temporal-sdk` / `temporal-testing` `1.35.0` for current-JDK ASM.
If the worker fails to start with an ASM / bytecode error, that version interaction is the
first suspect.
