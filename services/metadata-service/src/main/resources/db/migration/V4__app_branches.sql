-- App-definition branches (PHASE-4 §3/§6/§7/§9): state machines, SLAs, scheduled
-- jobs, and BPMN workflows persist as kind-discriminated child documents — the
-- md_pages pattern — so drafts and published bundles round-trip every branch the
-- runtime and the workflow/scheduler services consume.

CREATE TABLE md_definitions (
  id         uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  app_id     uuid NOT NULL,
  kind       text NOT NULL CHECK (kind IN ('state_machine', 'sla', 'scheduled_job', 'workflow')),
  api_name   text NOT NULL,
  document   jsonb NOT NULL,
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, app_id, kind, api_name)
);

CREATE INDEX md_definitions_app ON md_definitions (tenant_id, app_id, kind);
