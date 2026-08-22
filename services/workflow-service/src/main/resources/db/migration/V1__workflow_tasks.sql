-- Human tasks (PHASE-4 §5): approval/todo tasks with their lifecycle, delegation
-- chains (contextRef), and the SLA fields §6 populates when definitions land. The
-- event outbox mirrors the runtime's pattern: task.* events ride the creating
-- transaction and the relay publishes them to the spine at-least-once.

CREATE TABLE wf_tasks (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  type        text NOT NULL CHECK (type IN ('approval', 'todo')),
  entity_id   text NOT NULL,
  record_id   uuid NOT NULL,
  assignee    uuid,
  role        text,
  status      text NOT NULL CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED',
                                              'DELEGATED', 'ESCALATED', 'CANCELLED')),
  comment     text,
  due_at      timestamptz,
  warn_at     timestamptz,
  created_by  uuid NOT NULL,
  context_ref uuid,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT wf_task_target CHECK (assignee IS NOT NULL OR role IS NOT NULL)
);

CREATE INDEX wf_tasks_inbox ON wf_tasks (tenant_id, assignee, status);
CREATE INDEX wf_tasks_role ON wf_tasks (tenant_id, role, status);
CREATE INDEX wf_tasks_record ON wf_tasks (tenant_id, record_id) WHERE status = 'OPEN';
CREATE INDEX wf_tasks_chain ON wf_tasks (context_ref);

CREATE TABLE wf_event_outbox (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  task_id      uuid NOT NULL,
  event_type   text NOT NULL,
  payload      jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

CREATE INDEX wf_event_outbox_unpublished ON wf_event_outbox (created_at) WHERE published_at IS NULL;
