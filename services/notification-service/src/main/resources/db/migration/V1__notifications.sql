-- Notification v1 (PHASE-4 §8): the platform inbox plus per-user channel
-- preferences. Delivery events ride the outbox like every other spine producer.

CREATE TABLE nf_notifications (
  id         uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  user_id    uuid NOT NULL,
  category   text NOT NULL CHECK (category IN ('task-assignment', 'sla-warning')),
  title      text NOT NULL,
  body       text NOT NULL,
  event_id   text,            -- spine id: redeliveries collapse per recipient
  read_at    timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX nf_inbox ON nf_notifications (tenant_id, user_id, created_at);
CREATE UNIQUE INDEX nf_dedupe ON nf_notifications (tenant_id, user_id, event_id)
  WHERE event_id IS NOT NULL;

CREATE TABLE nf_preferences (
  tenant_id uuid NOT NULL,
  user_id   uuid NOT NULL,
  category  text NOT NULL,
  inbox     boolean NOT NULL DEFAULT true,
  email     boolean NOT NULL DEFAULT true,
  PRIMARY KEY (tenant_id, user_id, category)
);

CREATE TABLE nf_event_outbox (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  event_type   text NOT NULL,
  payload      jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

CREATE INDEX nf_event_outbox_unpublished ON nf_event_outbox (created_at)
  WHERE published_at IS NULL;
