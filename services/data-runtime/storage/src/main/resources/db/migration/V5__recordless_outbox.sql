-- Recordless app events (PHASE-7 §5's scheduled-flow shape): a publishEvent tail
-- on a scheduled flow has no record — the outbox row's record_id becomes nullable
-- (record-scoped families keep keying tenant:entity:record; a recordless app event
-- keys tenant:entity, KafkaOutboxRelay.keyFor). Existing rows are untouched.

ALTER TABLE event_outbox ALTER COLUMN record_id DROP NOT NULL;
