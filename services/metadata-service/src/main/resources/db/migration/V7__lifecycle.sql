-- App lifecycle (PHASE-8): version-bound suite runs, named environments, the
-- promotion audit trail, and the template catalog. One provisioning path — the
-- Phase 3 scratch mechanism — backs every environment (§2).

-- Suite-run artifacts are version-bound by content hash (ADR-010 #4): publish records
-- the draft bundle's sha256 on the version row; every run records the same hash of
-- the candidate it executed — the promotion gate matches them mechanically ("a
-- recorded green run of all app suites against exactly V", §4 item 1).
ALTER TABLE md_versions ADD COLUMN content_hash text;

CREATE TABLE md_suite_runs (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  app_id       uuid NOT NULL REFERENCES md_apps(id) ON DELETE CASCADE,
  suite        text NOT NULL,
  content_hash text,
  green        boolean NOT NULL,
  artifact     jsonb NOT NULL,
  run_at       timestamptz NOT NULL DEFAULT now(),
  run_by       uuid NOT NULL
);
CREATE INDEX md_suite_runs_app ON md_suite_runs (tenant_id, app_id, suite, run_at DESC);

-- Named environments pin promoted versions (§2): staging/prod are their own tenants
-- provisioned through the scratch mechanism (no per-run wipe); `dev` is the builder's
-- draft workspace — implicit, never a row here.
CREATE TABLE md_environments (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  app_id         uuid NOT NULL REFERENCES md_apps(id) ON DELETE CASCADE,
  env            text NOT NULL CHECK (env IN ('staging', 'prod')),
  pinned_version int NOT NULL,
  env_tenant_id  uuid,
  env_app_id     uuid,
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     uuid NOT NULL,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  updated_by     uuid NOT NULL,
  CONSTRAINT md_environments_app_env UNIQUE (tenant_id, app_id, env)
);

-- The promotion audit trail (§4 item 3): every hop, every override — an override is a
-- visible artifact rendered in change-set review forever, never a secret.
CREATE TABLE md_promotions (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  app_id         uuid NOT NULL REFERENCES md_apps(id) ON DELETE CASCADE,
  env            text NOT NULL,
  kind           text NOT NULL CHECK (kind IN ('promote', 'rollback')),
  from_version   int,
  to_version     int NOT NULL,
  overridden     boolean NOT NULL DEFAULT false,
  reason         text,
  gate_evidence  jsonb NOT NULL DEFAULT '{}'::jsonb,
  promoted_at    timestamptz NOT NULL DEFAULT now(),
  promoted_by    uuid NOT NULL
);
CREATE INDEX md_promotions_app ON md_promotions (tenant_id, app_id, env, promoted_at DESC);

-- The template catalog (§6): an exported app bundle (definitions only — tenant data
-- never rides the artifact; secrets never ride metadata, PHASE-6 §9) with listing
-- metadata. Catalog-only in v1: no commerce, no third-party publishing (§11 Q2).
CREATE TABLE md_templates (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  name        text NOT NULL,
  publisher   text,
  description text,
  version     text NOT NULL,
  bundle      jsonb NOT NULL,
  content_hash text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid NOT NULL,
  CONSTRAINT md_templates_tenant_name UNIQUE (tenant_id, name, version)
);

-- The Translations branch (PHASE-8 §7) joins the kind-discriminated documents.
ALTER TABLE md_definitions DROP CONSTRAINT md_definitions_kind_check;
ALTER TABLE md_definitions ADD CONSTRAINT md_definitions_kind_check
  CHECK (kind IN ('state_machine', 'sla', 'scheduled_job', 'workflow', 'report',
                  'dashboard', 'connector', 'webhook', 'credential', 'import_mapping',
                  'translation'));
