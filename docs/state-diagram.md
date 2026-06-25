# OrderWorkflow — state diagram

Each transition is labelled with what drives it: **(activity)** result, **(signal)** from
outside, or **(timer)**. Compensations run via `io.temporal.workflow.Saga`.

```mermaid
stateDiagram-v2
    [*] --> PLACED : start (Starter)

    PLACED --> PRICED : validateAndPrice (activity)
    PLACED --> FAILED : invalid input (activity, non-retryable)

    PRICED --> INVENTORY_RESERVED : reserveInventory ok (activity)
    PRICED --> CANCELLED : out of stock, substitution declined/expired (activity + substitution-deadline timer) [saga]

    INVENTORY_RESERVED --> PAID : takePayment ok (activity, retried)
    INVENTORY_RESERVED --> FAILED : payment declined (activity, non-retryable) [saga: release]
    INVENTORY_RESERVED --> CANCELLED : customerCancelled (signal) [saga: refund+release]

    PAID --> BREWING : baristaStarted (signal)
    PAID --> CANCELLED : customerCancelled (signal) [saga: refund+release]

    BREWING --> BREWING : brew-SLA breached (timer) -> escalate, stay open
    BREWING --> READY : allDrinksReady / all drinkReady (signal)
    BREWING --> CANCELLED : customerCancelled (signal) [waste, no refund]

    READY --> COLLECTED : customerCollected (signal)
    READY --> ABANDONED : pickup-expiry (timer) [waste, no refund]
    READY --> CANCELLED : customerCancelled (signal) [waste, no refund]

    COLLECTED --> COMPLETED : accrueLoyalty (activity)

    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
    ABANDONED --> [*]
```
