-- Job-completed notifications (PHASE-6 §7): the built-in category joining the v1
-- defaults per PHASE-4 §8's growth path (report-delivery was the first) — import
-- and export jobs notify their initiating user.

ALTER TABLE nf_notifications DROP CONSTRAINT nf_notifications_category_check;
ALTER TABLE nf_notifications ADD CONSTRAINT nf_notifications_category_check
  CHECK (category IN ('task-assignment', 'sla-warning', 'report-delivery', 'job-completed'));
