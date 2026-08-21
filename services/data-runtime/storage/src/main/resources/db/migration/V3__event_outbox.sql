-- Transactional outbox (PHASE-3 §4): domain events ride the record transaction as
-- outbox rows; the relay publishes them to the Kafka spine afterward and marks them
-- published — at-least-once delivery with consumer dedup on (event_id, consumer).

CREATE TABLE event_outbox (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  entity_id    text NOT NULL,
  record_id    uuid NOT NULL,
  event_type   text NOT NULL,
  payload      jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

CREATE INDEX event_outbox_unpublished ON event_outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX event_outbox_published ON event_outbox (published_at);
