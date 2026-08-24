-- Integration Service schema (PHASE-6 §2): the secret store (AES-GCM at rest —
-- §9), the delivery log + DLQ (idempotent deliveries, terminal failures park with
-- the payload preserved for builder replay — §3/§5/§6), inbound replay nonces (the
-- §11 HMAC matrix's replayed-signature leg), and the async job ledger with
-- per-row outcomes and checkpoints (§7).

CREATE TABLE it_secrets (
  tenant_id  uuid NOT NULL,
  ref        text NOT NULL,
  purpose    text NOT NULL CHECK (purpose IN ('webhook', 'credential')),
  ciphertext bytea NOT NULL,            -- iv(12) || tag(16) || body
  version    int  NOT NULL,
  active     boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, ref, version)
);
CREATE INDEX it_secrets_ref ON it_secrets (tenant_id, ref, active);

-- One row per (delivery identity): a delivery dedupes on its provider event id (or
-- body hash when absent) — at-least-once callers collapse to exactly-once effects.
CREATE TABLE it_deliveries (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  kind         text NOT NULL CHECK (kind IN ('connector', 'webhook_outbound', 'webhook_inbound')),
  target       text NOT NULL,
  dedupe_key   text NOT NULL,
  status       text NOT NULL CHECK (status IN ('pending', 'delivered', 'failed', 'dlq')),
  attempts     int NOT NULL DEFAULT 0,
  last_status  int,
  latency_ms   bigint,
  request_payload jsonb,
  response_summary text,
  error        text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, kind, target, dedupe_key)
);
CREATE INDEX it_deliveries_log ON it_deliveries (tenant_id, kind, created_at DESC);

-- The DLQ: terminal failures with the payload preserved for replay from the builder.
CREATE TABLE it_dlq (
  id         uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  kind       text NOT NULL CHECK (kind IN ('connector', 'webhook_outbound', 'webhook_inbound')),
  target     text NOT NULL,
  payload    jsonb NOT NULL,
  signature  text,
  error      text NOT NULL,
  attempts   int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  replayed_at timestamptz
);
CREATE INDEX it_dlq_pending ON it_dlq (tenant_id, kind, created_at DESC) WHERE replayed_at IS NULL;

-- Inbound replay nonces (§5): the ±5-minute window bounds replay in time; a seen
-- signature inside the window is a literal replay and rejects SIGNATURE_INVALID.
CREATE TABLE it_inbound_seen (
  tenant_id      uuid NOT NULL,
  hook_id        text NOT NULL,
  signature_hash text NOT NULL,
  seen_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, hook_id, signature_hash)
);

-- Async jobs (§7): import runs and entity/report export jobs — tenant data, never
-- metadata (the ImportDefinition itself is promoted with the app).
CREATE TABLE it_jobs (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  kind          text NOT NULL CHECK (kind IN ('import', 'export_entity', 'export_report')),
  status        text NOT NULL CHECK (status IN ('pending', 'running', 'paused', 'completed', 'failed')),
  app           text,
  entity        text,
  import_mapping text,
  report_id     text,
  run_as_role   text,
  params        jsonb,
  file_id       uuid,
  file_name     text,
  format        text,
  initiated_by  uuid NOT NULL,
  total_rows    bigint,
  processed_rows bigint NOT NULL DEFAULT 0,
  failed_rows   bigint NOT NULL DEFAULT 0,
  checkpoint    jsonb NOT NULL DEFAULT '{}',
  error         text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX it_jobs_tenant ON it_jobs (tenant_id, created_at DESC);

-- Per-row outcomes retained (§7: every job audited with per-item outcomes) — the
-- resume leg's exactly-once ledger: a row with an `ok` outcome is never re-applied.
CREATE TABLE it_job_rows (
  job_id    uuid NOT NULL REFERENCES it_jobs(id) ON DELETE CASCADE,
  row_index int NOT NULL,
  status    text NOT NULL CHECK (status IN ('ok', 'error', 'skipped')),
  record_id uuid,
  code      text,
  detail    text,
  PRIMARY KEY (job_id, row_index)
);

-- The spine outbox: connector.delivered / webhook.dispatched / import.progress ride
-- Kafka at-least-once from here (the PHASE-4 §2 per-service pattern).
CREATE TABLE it_event_outbox (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  event_type  text NOT NULL,
  payload     jsonb NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
