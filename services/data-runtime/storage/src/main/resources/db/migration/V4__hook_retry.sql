-- After-hook retry state (PHASE-3 §2 failure policy): hook.retry events consumed off
-- the spine land here; the scanner re-drives due rows with exponential backoff until
-- the hook runs clean, parks (terminal, never lost), or the row is parked as
-- non-re-drivable (script hooks are caller-context only — ADR-003 #2).
--
-- Like event_outbox, this is spine-side state: no RLS (the consumer and scanner run
-- without a request thread); tenant scoping is app-layer, from the event payload.

CREATE TABLE hook_retry_log (
  event_id       uuid PRIMARY KEY,      -- the spine event id: redelivery collapses here
  tenant_id      uuid NOT NULL,
  entity_id      text NOT NULL,         -- "<App>.<Entity>" entity key
  record_id      uuid NOT NULL,
  trigger_name   text NOT NULL,
  hook_name      text NOT NULL,
  kind           text NOT NULL,         -- flow | script
  attempt        int  NOT NULL,         -- attempts made (terminal rows carry the count)
  status         text NOT NULL,         -- pending | ok | parked
  next_attempt_at timestamptz NOT NULL,
  last_error     text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX hook_retry_due ON hook_retry_log (next_attempt_at)
  WHERE status = 'pending';
