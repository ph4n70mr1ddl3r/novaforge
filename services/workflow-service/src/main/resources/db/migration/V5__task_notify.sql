-- §6's onBreach.notify rides the task: "escalate to a role, notify, or both" was
-- authored as a switch the engine never read — every breach fanned out as an
-- sla-warning regardless. The flag resolves at task creation from the matching
-- SlaDefinition (absent onBreach authors the default, notify), stamps the breach
-- event's payload, and the Notification Service honors it.
ALTER TABLE wf_tasks ADD COLUMN notify_on boolean NOT NULL DEFAULT true;
