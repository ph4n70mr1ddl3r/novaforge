-- The publish outbox (the PHASE-4 §2 pattern every eventing service already rides):
-- metadata.published was sent synchronously INSIDE the publish transaction — a broker
-- outage held the DB connection (and the md_apps row lock) for the full 10-second send
-- timeout on every publish, and a send that succeeded just before a rollback emitted a
-- phantom event for a version row that does not exist. The outbox row commits atomically
-- with the version; a relay publishes at-least-once and retries until the broker returns.

CREATE TABLE md_event_outbox (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  app_id       uuid NOT NULL,
  event_type   text NOT NULL,
  payload      jsonb NOT NULL,
  traceparent  text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

CREATE INDEX md_event_outbox_pending ON md_event_outbox (created_at) WHERE published_at IS NULL;
