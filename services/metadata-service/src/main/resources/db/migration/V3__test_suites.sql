-- The Tests branch (ADR-010): builder test suites as versioned app metadata.

CREATE TABLE md_test_suites (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  app_id      uuid NOT NULL REFERENCES md_apps(id) ON DELETE CASCADE,
  api_name    text NOT NULL,
  document    jsonb NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid   NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  uuid   NOT NULL,
  CONSTRAINT md_test_suites_tenant_app_api UNIQUE (tenant_id, app_id, api_name)
);
