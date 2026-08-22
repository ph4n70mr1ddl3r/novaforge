#!/bin/bash
# Per-service databases on the shared instance. Phase 0 carries only Keycloak's
# database (PHASE-0 §7); novaforge-metadata and novaforge-data land with Phase 1
# (PHASE-1 §6) and are created here from day one so the compose stack needs no re-init
# when services gain persistence.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
  CREATE USER keycloak WITH PASSWORD 'keycloak';
  CREATE DATABASE keycloak OWNER keycloak;
  CREATE USER novaforge WITH PASSWORD 'novaforge';
  CREATE DATABASE novaforge_metadata OWNER novaforge;
  CREATE DATABASE novaforge_data OWNER novaforge;
  CREATE DATABASE novaforge_audit OWNER novaforge;
  CREATE DATABASE novaforge_workflow OWNER novaforge;
  CREATE DATABASE novaforge_notification OWNER novaforge;
  -- The data runtime's system paths (storage materializer backfill, restart catch-up)
  -- must read across tenants; user paths stay tenant-parameterized in the engine and
  -- RLS remains the backstop for every other role (ADR-006 defense-in-depth).
  ALTER ROLE novaforge BYPASSRLS;
EOSQL
