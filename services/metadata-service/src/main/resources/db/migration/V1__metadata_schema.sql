-- Metadata Service schema (PHASE-1 §4): draft workspace + immutable published versions.
-- Tenant-scoped by app.tenant session variable (set per checkout by TenantRlsDataSource-equivalent
-- wiring; the metadata DB is tenant-scoped, not RLS-guarded — definitions are per-tenant rows
-- with explicit tenant_id filters in every query).

CREATE TABLE md_apps (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  api_name        text NOT NULL,
  label           text,
  label_i18n      jsonb NOT NULL DEFAULT '{}'::jsonb,
  description     text,
  current_version int    NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid   NOT NULL,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid   NOT NULL,
  CONSTRAINT md_apps_tenant_api UNIQUE (tenant_id, api_name)
);

CREATE TABLE md_entities (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  app_id      uuid NOT NULL REFERENCES md_apps(id) ON DELETE CASCADE,
  api_name    text NOT NULL,
  document    jsonb NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid   NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  uuid   NOT NULL,
  CONSTRAINT md_entities_tenant_app_api UNIQUE (tenant_id, app_id, api_name)
);

CREATE TABLE md_pages (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  app_id      uuid NOT NULL REFERENCES md_apps(id) ON DELETE CASCADE,
  api_name    text NOT NULL,
  document    jsonb NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid   NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  uuid   NOT NULL,
  CONSTRAINT md_pages_tenant_app_api UNIQUE (tenant_id, app_id, api_name)
);

-- App-scoped settings (PHASE-1 §3: sequences from day one; currencies/shared enums land
-- on the same shape). `kind` partitions the settings branch.
CREATE TABLE md_settings (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  app_id      uuid NOT NULL REFERENCES md_apps(id) ON DELETE CASCADE,
  kind        text NOT NULL,
  api_name    text NOT NULL,
  document    jsonb NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid   NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  uuid   NOT NULL,
  CONSTRAINT md_settings_tenant_app_kind_api UNIQUE (tenant_id, app_id, kind, api_name)
);

-- Immutable publish snapshots (the runtime serves only these — design-time/runtime
-- split, ARCHITECTURE.md §6).
CREATE TABLE md_versions (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  app_id           uuid NOT NULL REFERENCES md_apps(id) ON DELETE CASCADE,
  version          int  NOT NULL,
  bundle           jsonb NOT NULL,
  breaking_changes jsonb NOT NULL DEFAULT '[]'::jsonb,
  acknowledged     boolean NOT NULL DEFAULT false,
  published_at     timestamptz NOT NULL DEFAULT now(),
  published_by     uuid  NOT NULL,
  CONSTRAINT md_versions_tenant_app_version UNIQUE (tenant_id, app_id, version)
);

CREATE INDEX md_entities_app ON md_entities (tenant_id, app_id);
CREATE INDEX md_pages_app ON md_pages (tenant_id, app_id);
CREATE INDEX md_settings_app ON md_settings (tenant_id, app_id);
CREATE INDEX md_versions_app ON md_versions (tenant_id, app_id, version DESC);
