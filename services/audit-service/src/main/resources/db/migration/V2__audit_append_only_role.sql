-- Mechanical append-only enforcement (PHASE-3 §5: "Append-only is enforced
-- mechanically — the store's role has no UPDATE/DELETE grants"): the audit
-- service's runtime role INSERTs and SELECTs, and can do nothing else to the
-- trail. The migration runs as the database owner (the service configures
-- spring.flyway.user for exactly this); the runtime pool connects as
-- novaforge_audit_app.

-- The role exists in the compose init script for fresh volumes; created here
-- when absent so test containers and pre-existing stacks converge too.
DO $do$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'novaforge_audit_app') THEN
    CREATE ROLE novaforge_audit_app LOGIN PASSWORD 'novaforge';
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO novaforge_audit_app', current_database());
END
$do$;

GRANT USAGE ON SCHEMA public TO novaforge_audit_app;

-- Partitioned parent: INSERT routes through it; SELECT reads the whole trail.
-- No UPDATE, no DELETE, no TRUNCATE, no DDL.
GRANT INSERT, SELECT ON audit_events TO novaforge_audit_app;

-- Partitions added later (the monthly rotation) grant identically at creation:
-- the default privileges of whichever role owns the objects (Flyway's connection).
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT INSERT, SELECT ON TABLES TO novaforge_audit_app;
