-- The durable audit trail (PHASE-3 §5 / ARCHITECTURE §2.8): append-only, partitioned
-- by month on occurred_at; a default partition catches the tail before the next
-- month's partition is added. Consumers dedup on (event_id, consumer) — at-least-once
-- delivery from the spine is idempotent here.

CREATE TABLE audit_events (
  event_id    uuid NOT NULL,
  tenant_id   uuid NOT NULL,
  entity_id   text NOT NULL,
  record_id   uuid NOT NULL,
  event_type  text NOT NULL,
  actor_id    uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  ingested_at timestamptz NOT NULL DEFAULT now(),
  payload     jsonb NOT NULL
) PARTITION BY RANGE (occurred_at);
-- A partitioned PK must include the partition key; (event_id, occurred_at) still
-- collapses identical redeliveries — an event's id and timestamp travel together.
ALTER TABLE audit_events ADD PRIMARY KEY (event_id, occurred_at);

-- v1: a DEFAULT partition keeps inserts total; month partitions rotate forward.
CREATE TABLE audit_events_default PARTITION OF audit_events DEFAULT;

CREATE INDEX audit_events_record ON audit_events (tenant_id, record_id, occurred_at DESC);
CREATE INDEX audit_events_entity ON audit_events (tenant_id, entity_id, occurred_at DESC);
