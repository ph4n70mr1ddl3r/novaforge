-- The email delivery marker (2026-08-31, fourteenth pass): a keyed send to a
-- recipient with inbox OFF produced no nf_notifications row, so nothing recorded
-- that their email was sent — every keyed replay (a retried scheduler window, a
-- redelivered job) re-emailed them despite the dedupe key. The marker row records
-- email-only deliveries on the same (tenant, user, event_id) dedupe index: a
-- replay collides and skips both legs, exactly like the inbox row always did.

CREATE TABLE nf_email_deliveries (
  tenant_id   uuid NOT NULL,
  user_id     uuid NOT NULL,
  event_id    text NOT NULL,
  delivered_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, user_id, event_id)
);
