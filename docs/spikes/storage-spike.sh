#!/usr/bin/env bash
# Storage spike for ADR-001 (PHASE-1 §2): hybrid JSONB + projections against a 1M-row
# dataset, comparing the two projection variants ARCHITECTURE.md §4 sketches:
#
#   A — pure view over rec_records, expression indexes on the base table
#   B — generated projection table (data duplicated, trigger-maintained),
#       STORED generated columns promoted from JSONB + regular indexes
#
# Measures: point read, filtered+sorted list (indexed field), unique-value lookup,
# write cost (insert incl. trigger for B), each 200 iterations on warm cache; records
# EXPLAIN ANALYZE plan shape for the list query. Run inside the compose Postgres:
#
#   docs/spikes/storage-spike.sh | tee docs/spikes/storage-spike-results.md
#
# Expected environment: podman with the novaforge-postgres container running.
set -euo pipefail

CONTAINER="${SPIKE_CONTAINER:-novaforge-postgres}"
DB="${SPIKE_DB:-novaforge_spike}"
ROWS="${SPIKE_ROWS:-1000000}"
ITERS=200

psqlc() { podman exec -i "$CONTAINER" psql -U postgres -q -X -v ON_ERROR_STOP=1 -d "$DB" "$@"; }

echo "# Storage spike — raw output ($(date -u +%Y-%m-%dT%H:%M:%SZ), ${ROWS} rows, ${ITERS} iterations"

podman exec -i "$CONTAINER" psql -U postgres -q -X -v ON_ERROR_STOP=1 -d postgres <<SQL
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${DB}' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS ${DB};
CREATE DATABASE ${DB};
SQL

psqlc <<SQL
\echo '## Schema'
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

\echo '## Seed'
\timing off
INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data)
SELECT gen_random_uuid(),
       '11111111-1111-4111-8111-111111111111'::uuid,
       'JournalEntry',
       '22222222-2222-4222-8222-222222222222'::uuid,
       '22222222-2222-4222-8222-222222222222'::uuid,
       jsonb_build_object(
         'reference',  'JE-' || lpad(g::text, 8, '0'),
         'entryDate',  to_char(date '2026-01-01' + (g % 240), 'YYYY-MM-DD'),
         'status',     CASE WHEN g % 2 = 0 THEN 'POSTED' ELSE 'DRAFT' END,
         'amount',     round((random() * 100000)::numeric, 2),
         'memo',       'spike row ' || g)
FROM generate_series(1, ${ROWS}) g;
ANALYZE rec_records;
SQL

echo
echo '## Variant A — pure view + expression indexes on base'
psqlc <<SQL
CREATE INDEX ix_a_entry_date ON rec_records (tenant_id, entity_id, (data->>'entryDate') DESC, id)
  WHERE NOT deleted;
CREATE UNIQUE INDEX ix_a_reference ON rec_records (tenant_id, entity_id, (data->>'reference'))
  WHERE NOT deleted;
CREATE VIEW rec_journal_entry_a AS
  SELECT id, tenant_id, version, created_at, updated_at, created_by, updated_by, deleted, data
  FROM rec_records WHERE entity_id = 'JournalEntry';
ANALYZE rec_records;
SQL

echo
echo '## Variant B — generated table, trigger-maintained, STORED generated columns'
psqlc <<SQL
CREATE TABLE rec_journal_entry_b (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  version     int  NOT NULL,
  created_at  timestamptz NOT NULL,
  updated_at  timestamptz NOT NULL,
  created_by  uuid NOT NULL,
  updated_by  uuid NOT NULL,
  deleted     boolean NOT NULL DEFAULT false,
  data        jsonb NOT NULL,
  reference   text GENERATED ALWAYS AS (data->>'reference') STORED,
  entry_date  text GENERATED ALWAYS AS (data->>'entryDate') STORED
);
CREATE INDEX ix_b_entry_date ON rec_journal_entry_b (tenant_id, entry_date DESC, id) WHERE NOT deleted;
CREATE UNIQUE INDEX ix_b_reference ON rec_journal_entry_b (tenant_id, reference) WHERE NOT deleted;

CREATE OR REPLACE FUNCTION sync_journal_entry_b() RETURNS trigger AS \$\$
BEGIN
  IF TG_OP IN ('INSERT', 'UPDATE') AND NEW.entity_id IS DISTINCT FROM 'JournalEntry' THEN
    RETURN NULL;
  END IF;
  IF TG_OP = 'DELETE' AND OLD.entity_id IS DISTINCT FROM 'JournalEntry' THEN
    RETURN NULL;
  END IF;
  IF TG_OP = 'INSERT' THEN
    INSERT INTO rec_journal_entry_b (id, tenant_id, version, created_at, updated_at, created_by, updated_by, deleted, data)
    VALUES (NEW.id, NEW.tenant_id, NEW.version, NEW.created_at, NEW.updated_at, NEW.created_by, NEW.updated_by, NEW.deleted, NEW.data);
  ELSIF TG_OP = 'UPDATE' THEN
    UPDATE rec_journal_entry_b
       SET version = NEW.version, updated_at = NEW.updated_at, updated_by = NEW.updated_by,
           deleted = NEW.deleted, data = NEW.data
     WHERE id = NEW.id;
  ELSIF TG_OP = 'DELETE' THEN
    DELETE FROM rec_journal_entry_b WHERE id = OLD.id;
  END IF;
  RETURN NULL;
END \$\$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entry_b AFTER INSERT OR UPDATE OR DELETE ON rec_records
  FOR EACH ROW EXECUTE FUNCTION sync_journal_entry_b();

INSERT INTO rec_journal_entry_b (id, tenant_id, version, created_at, updated_at, created_by, updated_by, deleted, data)
SELECT id, tenant_id, version, created_at, updated_at, created_by, updated_by, deleted, data
FROM rec_records WHERE entity_id = 'JournalEntry';
ANALYZE rec_journal_entry_b;
SQL

TENANT="'11111111-1111-4111-8111-111111111111'::uuid"

run_bench() {
  local label="$1" body="$2"
  podman exec -i "$CONTAINER" psql -U postgres -q -X -t -A -v ON_ERROR_STOP=1 -d "$DB" <<SQL 2>&1
DO \$do\$
DECLARE
  t0 timestamptz; total numeric := 0; max_ms numeric := 0; d numeric;
BEGIN
  FOR i IN 1..${ITERS} LOOP
    t0 := clock_timestamp();
    ${body};
    d := 1000 * extract(epoch from clock_timestamp() - t0);
    total := total + d;
    IF d > max_ms THEN max_ms := d; END IF;
  END LOOP;
  RAISE NOTICE 'RESULT|${label}|runs=${ITERS}|avg_ms=%|max_ms=%', round(total/${ITERS}, 3), round(max_ms, 3);
END \$do\$;
SQL
}

READ_ID=$(psqlc -t -A -c "SELECT id FROM rec_records WHERE entity_id='JournalEntry' LIMIT 1")

echo
echo '## Timings (avg/max ms over warm cache)'
run_bench "A|point read" "PERFORM data FROM rec_journal_entry_a WHERE id = '${READ_ID}'::uuid"
run_bench "B|point read" "PERFORM data FROM rec_journal_entry_b WHERE id = '${READ_ID}'::uuid"
run_bench "A|list p50"   "PERFORM id FROM rec_journal_entry_a WHERE tenant_id = ${TENANT} AND deleted = false AND (data->>'status') = 'POSTED' AND data->>'entryDate' >= '2026-05-01' ORDER BY data->>'entryDate' DESC, id LIMIT 50"
run_bench "B|list p50"   "PERFORM id FROM rec_journal_entry_b WHERE tenant_id = ${TENANT} AND deleted = false AND (data->>'status') = 'POSTED' AND entry_date >= '2026-05-01' ORDER BY entry_date DESC, id LIMIT 50"
run_bench "A|unique lookup" "PERFORM id FROM rec_records WHERE tenant_id = ${TENANT} AND entity_id='JournalEntry' AND data->>'reference' = 'JE-00050123' AND NOT deleted"
run_bench "B|unique lookup" "PERFORM id FROM rec_journal_entry_b WHERE tenant_id = ${TENANT} AND reference = 'JE-00050123' AND NOT deleted"
run_bench "A|insert"     "INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data) VALUES (gen_random_uuid(), ${TENANT}, 'JournalEntry', '22222222-2222-4222-8222-222222222222'::uuid, '22222222-2222-4222-8222-222222222222'::uuid, jsonb_build_object('reference','SPK-'||gen_random_uuid()::text,'entryDate','2026-08-01','status','DRAFT','amount',10))"
run_bench "B|insert"     "INSERT INTO rec_records (id, tenant_id, entity_id, created_by, updated_by, data) VALUES (gen_random_uuid(), ${TENANT}, 'JournalEntry', '22222222-2222-4222-8222-222222222222'::uuid, '22222222-2222-4222-8222-222222222222'::uuid, jsonb_build_object('reference','SPK-'||gen_random_uuid()::text,'entryDate','2026-08-01','status','DRAFT','amount',10))"

echo
echo '## Plans — list query'
echo '### Variant A'
psqlc -c "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT id FROM rec_journal_entry_a WHERE tenant_id = ${TENANT} AND deleted = false AND (data->>'status') = 'POSTED' AND data->>'entryDate' >= '2026-05-01' ORDER BY data->>'entryDate' DESC, id LIMIT 50"
echo '### Variant B'
psqlc -c "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT id FROM rec_journal_entry_b WHERE tenant_id = ${TENANT} AND deleted = false AND (data->>'status') = 'POSTED' AND entry_date >= '2026-05-01' ORDER BY entry_date DESC, id LIMIT 50"

echo
echo '## Table sizes'
psqlc -c "SELECT relname, pg_size_pretty(pg_total_relation_size(oid)) AS total_size FROM pg_class WHERE relname IN ('rec_records','rec_journal_entry_b','ix_a_entry_date','ix_a_reference','ix_b_entry_date','ix_b_reference') ORDER BY 1"
