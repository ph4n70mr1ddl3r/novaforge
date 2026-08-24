-- File Service schema (PHASE-6 §8): the attachment metadata entity —
-- fileId, entity/record binding, fileName, contentType, size, checksum (SHA-256),
-- and the virusScan lifecycle pending → clean | infected | skipped (the config-gated
-- ClamAV hook). Presigned URL grants land in the grant ledger with their expiry so
-- the §11 expiry check is enforceable server-side too.

CREATE TABLE fl_attachments (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  entity       text,
  record_id    uuid,
  object_key   text NOT NULL,
  file_name    text NOT NULL,
  content_type text NOT NULL DEFAULT 'application/octet-stream',
  size         bigint,
  checksum     text,
  virus_scan   text NOT NULL DEFAULT 'pending'
               CHECK (virus_scan IN ('pending', 'clean', 'infected', 'skipped')),
  uploaded_by  uuid NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX fl_attachments_record ON fl_attachments (tenant_id, entity, record_id);

-- Presigned grants: one row per issued URL with its expiry (§8's pinned 15 minutes).
CREATE TABLE fl_grants (
  id         uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  attachment uuid NOT NULL REFERENCES fl_attachments(id) ON DELETE CASCADE,
  mode       text NOT NULL CHECK (mode IN ('upload', 'download')),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- The quarantine/file-event outbox: file.quarantined and file.completed ride the
-- spine at-least-once (the audit service's integration listener family grows with
-- novaforge.file).
CREATE TABLE fl_event_outbox (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  event_type   text NOT NULL,
  payload      jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
