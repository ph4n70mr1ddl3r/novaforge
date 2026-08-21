-- Data Runtime base schema (PHASE-1 §6): rec_records per ARCHITECTURE.md §4 (ADR-001:
-- variant B — generated projection tables, trigger-maintained, created per entity at
-- publish time by the materializer, never here), the sequence state table, and the
-- platform authorization store (cross-tenant by design — no RLS; gated by the role
-- matrix, PHASE-1 §6).

CREATE TABLE rec_records (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  entity_id   text NOT NULL,
  version     int  NOT NULL DEFAULT 1,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid NOT NULL,
  updated_by  uuid NOT NULL,
  deleted     boolean NOT NULL DEFAULT false,
  data        jsonb NOT NULL
);

CREATE INDEX rec_records_tenant_entity ON rec_records (tenant_id, entity_id) WHERE NOT deleted;

-- RLS defense-in-depth (ADR-006): cast-safe text comparison — an unset variable sees
-- nothing (fail closed) and no uuid cast is attempted on non-uuid values.
ALTER TABLE rec_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE rec_records FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON rec_records USING (
  current_setting('app.tenant', true) <> ''
  AND tenant_id::text = current_setting('app.tenant', true));

-- Sequence state (PHASE-1 §5): gapless counters allocate by updating this row inside
-- the creating record's transaction (row lock serializes; rollback reverts the draw —
-- no number lost, no gap). `cached` sequences never touch this table (Redis blocks).
CREATE TABLE seq_state (
  tenant_id     uuid NOT NULL,
  app_id        text NOT NULL,
  sequence_name text NOT NULL,
  next_value    bigint NOT NULL,
  CONSTRAINT seq_state_pk PRIMARY KEY (tenant_id, app_id, sequence_name)
);

-- Platform authorization store (PHASE-1 §6/§7): cross-tenant by design — no RLS policy.
-- PHASE-2 §10's platform-admin API writes here; the matrix reads it at request time.
CREATE SCHEMA IF NOT EXISTS platform;

CREATE TABLE platform.tenants (
  id          uuid PRIMARY KEY,
  api_name    text NOT NULL UNIQUE,
  display_name text,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform.users (
  id          uuid PRIMARY KEY,
  username    text NOT NULL UNIQUE,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform.role_assignments (
  tenant_id uuid NOT NULL REFERENCES platform.tenants(id) ON DELETE CASCADE,
  user_id   uuid NOT NULL REFERENCES platform.users(id) ON DELETE CASCADE,
  role      text NOT NULL,
  CONSTRAINT role_assignments_pk PRIMARY KEY (tenant_id, user_id, role),
  CONSTRAINT role_assignments_role_check CHECK (role IN ('admin', 'builder', 'user'))
);

-- Bootstrap seed (PHASE-1 §7): the demo tenant + demo user holding admin/builder.
-- The bootstrap matrix is fail closed — `user` holds no CRUD grant until Phase 2's
-- role editors make grants authorable.
INSERT INTO platform.tenants (id, api_name, display_name)
VALUES ('11111111-1111-4111-8111-111111111111', 'demo', 'Demo Tenant');

INSERT INTO platform.users (id, username)
VALUES ('33333333-3333-4333-8333-333333333333', 'demo');

INSERT INTO platform.role_assignments (tenant_id, user_id, role) VALUES
  ('11111111-1111-4111-8111-111111111111', '33333333-3333-4333-8333-333333333333', 'admin'),
  ('11111111-1111-4111-8111-111111111111', '33333333-3333-4333-8333-333333333333', 'builder'),
  ('11111111-1111-4111-8111-111111111111', '33333333-3333-4333-8333-333333333333', 'user');
