-- Identical payment table on every shard. A store's payments all live on one shard
-- (routing = floorMod(hash(storeId), shard.count)); the worker scans all shards.
CREATE TABLE IF NOT EXISTS payment (
    id                uuid PRIMARY KEY,
    request_id        uuid NOT NULL,
    store_id          text NOT NULL,
    coffee_type       text NOT NULL,
    price             numeric(10, 2) NOT NULL,
    currency          text NOT NULL,
    loyalty_card_id   text,
    status            text NOT NULL,
    remote_payment_id text,
    attempts          int  NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- The worker repeatedly claims PENDING rows oldest-first; index supports that scan.
CREATE INDEX IF NOT EXISTS idx_payment_status_created ON payment (status, created_at);
