-- The breach re-fire fence (eighteenth pass): the two stay-OPEN breach branches
-- (notify-only SLAs, and escalation targets whose role has no holders) had no
-- marker — every 5 s scanner pass re-selected the still-open, still-overdue task
-- and re-emitted sla.breach with a fresh event id, so no consumer dedupe could
-- collapse it: a new inbox row and a new email per recipient per pass, forever,
-- plus an unbounded spine/audit stream per wedged task. The flag mirrors
-- sla_warned's conditional flip: the breach fires exactly once; the task stays
-- OPEN and resolvable (the sixteenth-pass semantics unchanged).

ALTER TABLE wf_tasks ADD COLUMN sla_breached boolean NOT NULL DEFAULT false;
