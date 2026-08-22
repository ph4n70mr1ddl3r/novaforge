-- Durable flow suspension (PHASE-4 §4): a requestApproval step's flow instance —
-- where it resumes, what a reject runs, and the unanimity bookkeeping for `all`
-- mode. Resolution re-enters the Data Runtime's compiled-graph engine (system
-- principal); the row records the outcome of that re-entry.

CREATE TABLE wf_suspended_flows (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  app             text NOT NULL,
  entity_api_name text NOT NULL,
  entity_key      text NOT NULL,
  record_id       uuid NOT NULL,
  hook_name       text NOT NULL,
  step_id         text NOT NULL,
  after_step      text,
  on_reject       text,              -- FlowStep subgraph JSON (the step's own)
  mode            text NOT NULL CHECK (mode IN ('any', 'all')),
  needed          int NOT NULL,      -- approvals needed to resume (1 for `any`)
  approvals       int NOT NULL DEFAULT 0,
  status          text NOT NULL CHECK (status IN ('SUSPENDED', 'RESUMED', 'REJECTED',
                                                  'FAILED')),
  last_error      text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX wf_suspended_record ON wf_suspended_flows (tenant_id, record_id);

-- The approval task's link to its suspended instance (delegation chains keep
-- contextRef; the suspension link is its own column).
ALTER TABLE wf_tasks ADD COLUMN instance_id uuid;
CREATE INDEX wf_tasks_instance ON wf_tasks (instance_id) WHERE status = 'OPEN';
