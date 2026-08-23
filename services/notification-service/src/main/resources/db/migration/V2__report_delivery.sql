-- Scheduled report delivery (PHASE-5 §7): the built-in report-delivery category
-- joins Notification v1's defaults — the category CHECK widens to admit it.

ALTER TABLE nf_notifications DROP CONSTRAINT nf_notifications_category_check;
ALTER TABLE nf_notifications ADD CONSTRAINT nf_notifications_category_check
  CHECK (category IN ('task-assignment', 'sla-warning', 'report-delivery'));
