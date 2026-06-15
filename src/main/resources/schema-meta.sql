-- The non-sharded metadata DB: one row per bulk request, holding its aggregate
-- status. Separate from the shards because a request can span many stores/shards.
CREATE TABLE IF NOT EXISTS import_request (
    id         uuid PRIMARY KEY,
    status     text NOT NULL,
    total      int  NOT NULL,
    processed  int  NOT NULL DEFAULT 0,
    failed     int  NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
