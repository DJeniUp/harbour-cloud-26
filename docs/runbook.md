# OrderWorkflow — live demo runbook

Copy-paste commands for the local Temporal dev server. Three long-lived terminals plus a
scratch one for `runStarter` / `temporal` calls.

## 0. One-time setup (3 terminals)

```bash
# Terminal A — Temporal dev server. Web UI: http://localhost:8233 (gRPC :7233)
temporal server start-dev

# Terminal B — hw1 Payments API on :8080 (needed for real charges: F1, F4, F5, F6)
./gradlew bootRun

# Terminal C — the Temporal worker. Shrink the demo timers so F3/F5 finish in under a minute.
DEMO_BREW_SLA_SECONDS=10 DEMO_SUBSTITUTION_SECONDS=15 DEMO_PICKUP_SECONDS=20 ./gradlew runWorker
```

> The timer values above are read by the **Starter** (terminal D), not the worker — but exporting
> them in both shells keeps things consistent. Set them in the shell you run `runStarter` from:
>
> ```bash
> export DEMO_BREW_SLA_SECONDS=10 DEMO_SUBSTITUTION_SECONDS=15 DEMO_PICKUP_SECONDS=20 DEMO_PAYMENT_FAIL_TIMES=3
> ```

Each `start` prints a `workflowId` like `order-ord-1718000000000`. Use it in follow-up signals.
In the Web UI (`:8233`) open the workflow to watch **Event History** and run the `getStatus`
query (Query tab → `getStatus`).

---

## F1 — payment times out 3×, then succeeds (exactly one charge)

```bash
./gradlew runStarter -Pargs="start F1"            # paymentMode=fake-timeout, fails DEMO_PAYMENT_FAIL_TIMES then succeeds
# advance the rest of the happy path (use the printed workflowId):
./gradlew runStarter -Pargs="signal <wfId> baristaStarted"
./gradlew runStarter -Pargs="signal <wfId> allDrinksReady"
./gradlew runStarter -Pargs="signal <wfId> customerCollected"
```

- **Expected terminal state:** `COMPLETED`.
- **Web UI:** the `TakePayment` activity shows multiple attempts (3 failures then success) with
  growing backoff; the workflow then advances PAID → BREWING → READY → COLLECTED → COMPLETED.
- **Confirm exactly one charge in hw1:**

```bash
curl -s "http://localhost:8080/api/v1/payments?storeId=store-london-01" | jq
```

There is exactly **one** payment whose idempotency key is `order-<orderId>-pay` (the
`<orderId>` is the part after `order-` in the workflowId).

---

## F2 — payment permanently declined

```bash
./gradlew runStarter -Pargs="start F2"            # paymentMode=fake-decline
```

- **Expected terminal state:** `FAILED`.
- **Web UI:** `TakePayment` fails non-retryably (`PaymentDeclinedException`), then
  `ReleaseInventory` runs (saga compensation). No `AccrueLoyalty`. `getStatus` → `FAILED`.
- **hw1:** no new payment for this order (the charge never succeeded).

---

## F3 — out of stock + substitution

```bash
./gradlew runStarter -Pargs="start F3"            # reserveInventory throws OutOfStockException (COLD_BREW)
```

Then choose one branch within DEMO_SUBSTITUTION_SECONDS (15s):

```bash
# (a) accept -> substitute AMERICANO, proceeds; finish the happy path:
./gradlew runStarter -Pargs="signal <wfId> substitutionResponse true"
./gradlew runStarter -Pargs="signal <wfId> baristaStarted"
./gradlew runStarter -Pargs="signal <wfId> allDrinksReady"
./gradlew runStarter -Pargs="signal <wfId> customerCollected"     # -> COMPLETED

# (b) decline -> CANCELLED:
./gradlew runStarter -Pargs="signal <wfId> substitutionResponse false"

# (c) do nothing -> substitution-deadline timer fires -> CANCELLED
```

- **Expected terminal state:** `COMPLETED` (accept) or `CANCELLED` (decline / timeout).
- **Web UI:** a `notifyCustomer` activity records the substitution offer; on timeout the
  `Timer` for the substitution-deadline fires; `getStatus` shows the outcome.
- Equivalent `temporal` CLI: `temporal workflow signal --workflow-id <wfId> --name substitutionResponse --input true`.

---

## F4 — customer cancels

**Pre-brew (refund + release):**

```bash
./gradlew runStarter -Pargs="start F4 prebrew"
./gradlew runStarter -Pargs="signal order-prebrew customerCancelled"   # before baristaStarted
```

- **Terminal state:** `CANCELLED`. Web UI shows `RefundPayment` **and** `ReleaseInventory` (saga).

**Post-brew (waste, no refund):**

```bash
./gradlew runStarter -Pargs="start F4 postbrew"
./gradlew runStarter -Pargs="signal order-postbrew baristaStarted"
./gradlew runStarter -Pargs="signal order-postbrew allDrinksReady"
./gradlew runStarter -Pargs="signal order-postbrew customerCancelled"  # after drinks made
```

- **Terminal state:** `CANCELLED`. Web UI shows **no** `RefundPayment` and **no**
  `ReleaseInventory` (drinks wasted per policy).

---

## F5 — customer never collects

```bash
./gradlew runStarter -Pargs="start F5"
./gradlew runStarter -Pargs="signal <wfId> baristaStarted"
./gradlew runStarter -Pargs="signal <wfId> allDrinksReady"
# then wait out DEMO_PICKUP_SECONDS (20s) — do NOT send customerCollected
```

- **Expected terminal state:** `ABANDONED`.
- **Web UI:** the pickup-expiry `Timer` fires and beats `customerCollected`; no `RefundPayment`.

---

## F6 — worker crash mid-order (replay, no double charge)

```bash
./gradlew runStarter -Pargs="start happy crashtest"
./gradlew runStarter -Pargs="signal order-crashtest baristaStarted"     # now in BREWING

# Crash the worker:  Ctrl-C  the `runWorker` process in Terminal C.
# Restart it:
DEMO_BREW_SLA_SECONDS=10 DEMO_SUBSTITUTION_SECONDS=15 DEMO_PICKUP_SECONDS=20 ./gradlew runWorker

# Finish the order — it resumed from history at BREWING:
./gradlew runStarter -Pargs="signal order-crashtest allDrinksReady"
./gradlew runStarter -Pargs="signal order-crashtest customerCollected"  # -> COMPLETED
```

- **Expected terminal state:** `COMPLETED` after restart.
- **Web UI:** the single workflow execution continues across the restart (one continuous
  Event History); `getStatus` resumes from `BREWING`.
- **Confirm exactly one charge** (the charge happened before the crash and is not repeated):

```bash
curl -s "http://localhost:8080/api/v1/payments?storeId=store-london-01" | jq \
  '[.[] | select(.loyaltyCardId=="card-loyalty-001")]'
```

Still exactly one payment for `order-crashtest-pay` — replay reuses the stable idempotency key,
so no double charge.
