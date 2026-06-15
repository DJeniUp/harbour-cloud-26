# StarHarbour Payments Service

A Spring Boot REST service that records coffee-shop payments for the fictional **StarHarbour** chain.
Built as the running example for the [Harbour.Space](https://harbour.space/) **Cloud Computing for Software Engineers** course — each iteration of the codebase introduces a new distributed-systems concept on top of this foundation.

---

## What it does

The service exposes a small Payments API used by point-of-sale terminals in StarHarbour stores:

| Concept | Where it lives |
|---|---|
| **Idempotent writes** — a terminal can safely retry after a network timeout without creating a duplicate payment | `PaymentService` + `PaymentRepository` (keyed on `Store-Id` × `Idempotency-Key`) |
| **Input validation** — structured `400 Bad Request` responses via Jakarta Bean Validation | `PaymentRequest`, `PaymentExceptionHandler` |
| **Network fault injection** — Toxiproxy sits in front of the app so you can simulate latency, packet loss, and timeouts without changing a line of code | `compose.yaml`, `toxiproxy.json` |
| **Transaction viewer UI** — a vanilla-JS single-page app served as a static resource | `src/main/resources/static/index.html` |

### API surface

All endpoints are under `/api/v1/payments`.

#### Register a payment
```
POST /api/v1/payments
Store-Id: <store-id>          # required — identifies the store
Idempotency-Key: <uuid>       # optional — supply to make retries safe
Content-Type: application/json

{
  "coffeeType": "LATTE",      // see CoffeeType enum for all values
  "price": 3.50,
  "currency": "EUR",          // ISO-4217, e.g. EUR / USD / GBP
  "loyaltyCardId": "card-123"
}
```
Returns `201 Created` for a new payment, `200 OK` when the same `Idempotency-Key` has already been processed (the original payment is echoed back unchanged).

#### List payments for a store
```
GET /api/v1/payments?storeId=<store-id>
```

#### Get a single payment
```
GET /api/v1/payments/{paymentId}
```

### Coffee types
`ESPRESSO` · `DOUBLE_ESPRESSO` · `AMERICANO` · `LATTE` · `CAPPUCCINO` · `FLAT_WHITE` · `MOCHA` · `CORTADO` · `MACCHIATO` · `COLD_BREW`

---

## Requirements

| Tool | Version |
|---|---|
| Java | 25 (set via `.sdkmanrc` — run `sdk use` if you use [SDKMAN](https://sdkman.io/)) |
| Docker & Docker Compose | any recent version |
| Gradle | bundled via `./gradlew` — no separate install needed |

---

## Running the application

### 1. Start the Spring Boot app (with Toxiproxy sidecar)

Spring Boot's Docker Compose integration starts Toxiproxy automatically when you launch the app.

```bash
./gradlew bootRun
```

The app is now reachable on two ports:

| Port | What's there |
|---|---|
| **8080** | Spring Boot directly |
| **9091** | Toxiproxy proxy — use this to experience injected faults |
| **8474** | Toxiproxy management API |

Open the transaction viewer UI at **http://localhost:8080** (or **http://localhost:9091** to route through the proxy).

### 2. Run tests

```bash
./gradlew test
```

Tests use MockMvc — no Docker needed.

---

## Trying idempotency

```bash
# First call — creates the payment (201)
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/api/v1/payments \
  -H "Store-Id: store-london-01" \
  -H "Idempotency-Key: order-abc-123" \
  -H "Content-Type: application/json" \
  -d '{"coffeeType":"LATTE","price":3.50,"currency":"EUR","loyaltyCardId":"card-999"}'

# Exact same call — replays the original payment (200, same paymentId)
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/api/v1/payments \
  -H "Store-Id: store-london-01" \
  -H "Idempotency-Key: order-abc-123" \
  -H "Content-Type: application/json" \
  -d '{"coffeeType":"LATTE","price":3.50,"currency":"EUR","loyaltyCardId":"card-999"}'
```

## Injecting network faults via Toxiproxy

Point your client at **port 9091** and use the Toxiproxy management API on **port 8474**.

```bash
# Add 2 s latency with 500 ms jitter
curl -X POST http://localhost:8474/proxies/spring-boot-app/toxics \
  -H "Content-Type: application/json" \
  -d '{"name":"latency","type":"latency","attributes":{"latency":2000,"jitter":500}}'

# Simulate a total connection timeout
curl -X POST http://localhost:8474/proxies/spring-boot-app/toxics \
  -H "Content-Type: application/json" \
  -d '{"name":"timeout","type":"timeout","attributes":{"timeout":0}}'

# Remove the toxic and restore normal behaviour
curl -X DELETE http://localhost:8474/proxies/spring-boot-app/toxics/latency
```

---

## Project layout

```
harbour-cloud-26/
├── src/
│   ├── main/
│   │   ├── java/space/harbour/cloud/
│   │   │   ├── CloudApplication.java          # Spring Boot entry point
│   │   │   └── payments/
│   │   │       ├── Payment.java               # Domain record
│   │   │       ├── PaymentRequest.java        # Validated request body
│   │   │       ├── PaymentResponse.java       # API response shape
│   │   │       ├── CoffeeType.java            # Enum of coffee varieties
│   │   │       ├── PaymentController.java     # REST endpoints
│   │   │       ├── PaymentService.java        # Idempotency logic
│   │   │       ├── PaymentRepository.java     # In-memory store
│   │   │       ├── PaymentConfig.java         # Clock bean
│   │   │       └── PaymentExceptionHandler.java # 400 error shaping
│   │   └── resources/
│   │       ├── application.properties
│   │       └── static/index.html             # Transaction viewer UI
│   └── test/
│       └── java/space/harbour/cloud/payments/
│           └── PaymentControllerTest.java
├── compose.yaml          # Toxiproxy sidecar
├── toxiproxy.json        # Proxy config: 9091 → localhost:8080
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Course context

This repository is the practical companion to the **Distributed Systems & Cloud** lecture series. The storage layer is intentionally in-memory (a `ConcurrentHashMap`) — later modules swap it for a real database, add messaging via Kafka, and deploy to AWS. Each change targets a single distributed-systems concept so students can study it in isolation.


## Asynchronous bulk processing + sharded relational store

Homework 3 turns payment processing **asynchronous** and replaces the in-memory map
(for the bulk path) with a **manually sharded** set of Postgres instances plus a
separate **metadata** database. The synchronous single-payment endpoint
(`POST /api/v1/payments`) is unchanged and still backed by the in-memory store — in
the async pipeline that same endpoint plays the role of the *remote* Payments API.

Code lives in `space.harbour.cloud.payments.async` (the API + worker) and
`space.harbour.cloud.payments.shard` (datasources, routing, repositories, schema).

### The async flow

```
POST /api/v1/payments/bulk            (1)            ┌─────────── meta DB ───────────┐
   │  JSON array of payments                          │ import_request(id,status,     │
   ▼                                                   │   total,processed,failed)     │
BulkPaymentService.accept()  ──── create request ────▶│  status=PENDING, total=N      │
   │                                                   └───────────────────────────────┘
   │  for each payment: insertPending() routed by storeId
   ▼
┌──────── shard 0 ────────┐   ┌──────── shard 1 ────────┐   ...   floorMod(hash(storeId), N)
│ payment(... status=     │   │ payment(... status=     │
│   PENDING, request_id)  │   │   PENDING, request_id)  │
└─────────────────────────┘   └─────────────────────────┘
   ▲ 202 Accepted {requestId}                              (returns immediately — no inline work)

@Scheduled AsyncPaymentWorker  (every worker.interval)     (2)
   │  claimPending(): SELECT ... FOR UPDATE SKIP LOCKED across ALL shards
   │                  → flip claimed rows PENDING → PROCESSING (short txn, then commit)
   ▼
RemotePaymentClient.createPayment()  ── POST {remote.base-url}/api/v1/payments
   │   Idempotency-Key = payment.id, Store-Id = storeId
   │   capped exponential backoff + jitter on transient (I/O, 408/429/5xx);
   │   never retry permanent 4xx; follows 302 from the load balancer
   ▼
 success → payment.status = DONE (+ remote_payment_id);  request.processed++
 4xx     → payment.status = FAILED;                      request.failed++
 transient, gave up → requeue to PENDING (retried next tick) until worker.max-attempts → FAILED
   │
   ▼  when processed + failed == total  →  request.status = DONE

GET /api/v1/payments/requests/{id}     (3)  → {requestId,status,total,processed,failed}  (404 if unknown)
```

### Request status lifecycle

An `import_request` row starts **`PENDING`** (total = number of payments, processed =
failed = 0). The worker atomically bumps `processed` / `failed` per payment; the row
flips to **`DONE`** exactly when `processed + failed == total`. (`DONE` here means
"every payment reached a terminal state" — some may be `FAILED`.) Each payment row
moves `PENDING → PROCESSING → DONE | FAILED`, with transient give-ups bounced back to
`PENDING` for a later pass.

### Sharding scheme

- **Routing:** `shardOf(storeId) = Math.floorMod(storeId.hashCode(), shard.count)`
  (`ShardResolver`). `String.hashCode()` is specified by the JLS, so the mapping is
  stable across JVMs/restarts; `floorMod` keeps the result in `[0, count)` for negative
  hashes. Keying on `storeId` **co-locates a store's payments on one shard**.
- **Static topology, no resharding:** `shard.count` and the per-shard datasources are
  fixed at boot; `ShardConfig` builds one `HikariDataSource` + `JdbcTemplate` per shard
  programmatically. There is no single primary datasource, so
  `DataSourceAutoConfiguration` is excluded (on both the payments app and the balancer).
- **Why metadata is separate:** a single bulk request can contain payments for many
  stores → many shards, so its aggregate status has no natural shard home. It lives in
  its own **meta** DB (`import_request`), queried as one authoritative source of truth.
- **`FOR UPDATE SKIP LOCKED`:** the worker claims a batch by locking pending rows and
  skipping any already locked by another pass, so it is safe to run repeatedly or scale
  out — no two passes ever grab the same payment.
- **Idempotency carried into async:** the payment's `id` (generated up front, stable
  when an `idempotencyKey` is supplied) is sent as the remote `Idempotency-Key`. A
  worker retry, redeploy, or crash-restart therefore **never creates a duplicate remote
  entry** — the homework-1 reliability guarantee, now end-to-end.

### Configuration properties

| Property | Default | Meaning |
|---|---|---|
| `shard.enabled` | `true` | Master switch for the sharded store + async pipeline. Set to `false` (test profile) to boot without any database. |
| `shard.count` | `2` | Number of shards. **Must equal** the number of `shard.datasources` entries. |
| `shard.datasources[i].{url,username,password}` | — | JDBC coordinates of each shard. |
| `meta.datasource.{url,username,password}` | — | JDBC coordinates of the (non-sharded) metadata DB. |
| `remote.base-url` | `http://localhost:8080` | Base URL of the remote Payments API the worker POSTs to. |
| `worker.interval` | `1000` | Worker fixed-delay in ms between passes. |
| `worker.batch-size` | `50` | Max rows claimed per shard per pass. |
| `worker.max-attempts` | `10` | After this many transient give-ups a payment is marked `FAILED`. |

Schema (`schema-shard.sql` on every shard, `schema-meta.sql` on meta) is created on
startup by `SchemaInitializer` (a `CommandLineRunner` using `CREATE TABLE IF NOT
EXISTS`, so it is safe on every boot).

### API

```
# Submit a batch — returns 202 Accepted, processed in the background
POST /api/v1/payments/bulk
Content-Type: application/json

[
  {"storeId":"store-london-01","coffeeType":"LATTE","price":3.50,"currency":"EUR","loyaltyCardId":"card-1","idempotencyKey":"lon-1"},
  {"storeId":"store-tokyo-04","coffeeType":"COLD_BREW","price":4.00,"currency":"JPY","loyaltyCardId":"card-5"}
]
→ 202  {"requestId":"<uuid>"}

# Poll aggregate status
GET /api/v1/payments/requests/{requestId}
→ 200  {"requestId":"...","status":"PENDING|DONE","total":N,"processed":N,"failed":N}   (404 if unknown)
```

`idempotencyKey` per item is optional; when present the payment id is derived
deterministically from `(storeId, idempotencyKey)`, so re-submitting the same bulk
request reuses the same remote idempotency key.

### Running

Three Postgres services are defined in `compose.yaml`: `shard0` (host port **5432**),
`shard1` (**5433**), and `meta` (**5434**), all with user/password `app/app`.

```bash
# Option A — let Spring Boot's docker-compose integration start everything:
./gradlew bootRun

# Option B — bring the databases up yourself first:
docker compose up -d
./gradlew bootRun
```

> **Port note:** if you already run Postgres locally on 5432, free it first or remap
> `shard0` (e.g. host port `15432`) in `compose.yaml` and point
> `shard.datasources[0].url` at it — the verification below was run that way.

### Verifying it end-to-end

```bash
# 1. Submit a bulk request spanning several stores; capture the requestId
RID=$(curl -s -X POST http://localhost:8080/api/v1/payments/bulk \
  -H "Content-Type: application/json" \
  -d '[{"storeId":"store-london-01","coffeeType":"LATTE","price":3.50,"currency":"EUR","loyaltyCardId":"c1","idempotencyKey":"lon-1"},
       {"storeId":"store-tokyo-04","coffeeType":"COLD_BREW","price":4.00,"currency":"JPY","loyaltyCardId":"c2","idempotencyKey":"tok-1"}]' \
  | grep -o '"requestId":"[^"]*"' | cut -d'"' -f4)
echo "$RID"

# 2. Show the payments landed on DIFFERENT shards
docker compose exec shard0 psql -U app -d payments -c \
  "SELECT store_id, count(*), string_agg(distinct status,',') FROM payment GROUP BY store_id;"
docker compose exec shard1 psql -U app -d payments -c \
  "SELECT store_id, count(*), string_agg(distinct status,',') FROM payment GROUP BY store_id;"

# 3. Poll status: watch PENDING → DONE as the worker processes
curl -s http://localhost:8080/api/v1/payments/requests/$RID

# 4. Confirm the remote system received one entry per payment (no duplicates)
curl -s "http://localhost:8080/api/v1/payments?storeId=store-london-01"
```

To prove the idempotency-key path (what a worker retry/restart does), re-POST a
processed payment to the remote with `Idempotency-Key` = that payment's id: it returns
`200` with the original payment, and the store's remote count does not grow.

Observed in a live run (40-payment batch): status moved
`PENDING(0) → PENDING(15) → PENDING(31) → DONE(40)`; `store-tokyo-04` rows landed on
shard1 while the other stores landed on shard0; and 46 payments produced exactly 46
remote entries — one per payment, none duplicated.
