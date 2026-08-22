-- BPMN v1 (PHASE-4 §9): the deployment registry (publish-driven like every
-- registry — §7's split), the event-start dedupe table, and the bridge between
-- engine user tasks and the §5 inbox.

-- Deployed process definitions: content-hash idempotency (a re-sync of unchanged
-- BPMN deploys nothing), audible failure (status + error, retried next pass).
CREATE TABLE wf_process_deployments (
  id                   uuid PRIMARY KEY,   -- deterministic tenant:app:workflow
  tenant_id            uuid NOT NULL,
  app                  text NOT NULL,
  workflow_id          text NOT NULL,      -- the process key (== <process id>)
  content_hash         text NOT NULL,
  event_starts         jsonb,              -- the subscriptions, for event-start matching
  deployment_id        text,               -- Flowable's deployment id
  process_definition_id text,
  status               text NOT NULL CHECK (status IN ('DEPLOYED', 'FAILED', 'REMOVED')),
  error                text,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, app, workflow_id)
);

-- Event-start dedupe: the spine is at-least-once, so (event id, workflow) is the
-- key — a redelivery collapses per workflow, and two subscriptions matching the
-- same event both start. The row rides the same transaction as the engine start.
CREATE TABLE wf_process_starts (
  event_id    uuid NOT NULL,
  tenant_id   uuid NOT NULL,
  workflow_id text NOT NULL,
  instance_id text,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, workflow_id)
);

-- The §5-inbox bridge: a wf_tasks row linked to its engine task. Delegation is
-- rejected for these rows in v1 (Flowable's single-task model does not map to
-- replacement-task chains — §9).
CREATE TABLE wf_process_tasks (
  task_id             uuid PRIMARY KEY,
  engine_task_id      text NOT NULL UNIQUE,
  process_instance_id text NOT NULL,
  workflow_id         text NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX wf_process_deployments_lookup ON wf_process_deployments (tenant_id, status);

-- Processes started by the Scheduler may carry no record context; inbox rows
-- for them live without one (§9 — businessKey optional).
ALTER TABLE wf_tasks ALTER COLUMN record_id DROP NOT NULL;
