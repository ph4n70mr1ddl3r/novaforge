-- The resume idempotency key (2026-08-31, sixteenth pass): the workflow service's
-- remote resume and its local commit are one dual-write — when the runtime leg
-- succeeds and the workflow-side transaction fails to commit, the retry re-entered
-- the engine and re-ran the approval subgraph a second time (duplicate transitions,
-- duplicate created records). The suspension's instanceId keys a claim row inside
-- the runtime's own resume transaction: the first execution inserts it, and a
-- retried delivery of the same key observes the claim and skips the re-entry.

CREATE TABLE resume_claims (
  instance_id uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  record_id   uuid NOT NULL,
  approved    boolean NOT NULL,
  claimed_at  timestamptz NOT NULL DEFAULT now()
);
