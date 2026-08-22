-- SLA tracking (PHASE-4 §6): one warn per task, and the escalation target the
-- scanner acts on at breach — sourced from a matching SLADefinition's onBreach or
-- the requestApproval step's own escalateTo, resolved at task creation.

ALTER TABLE wf_tasks ADD COLUMN sla_warned boolean NOT NULL DEFAULT false;
ALTER TABLE wf_tasks ADD COLUMN escalate_to text;
CREATE INDEX wf_tasks_due ON wf_tasks (due_at) WHERE status = 'OPEN';
