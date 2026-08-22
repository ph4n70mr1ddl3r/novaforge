-- The cron registry (PHASE-4 §7): runtime state only — job definitions are
-- versioned metadata synced from the Metadata Service's published surface. The
-- lease table is the distributed lock (single-fire under concurrent replicas);
-- missed windows skip (misfire: fire once, skip missed).

CREATE TABLE sched_jobs (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  app          text NOT NULL,
  name         text NOT NULL,
  cron         text NOT NULL,
  target       text NOT NULL CHECK (target IN ('flow', 'script', 'processStart', 'report')),
  params       jsonb NOT NULL DEFAULT '{}',
  enabled      boolean NOT NULL DEFAULT true,
  next_fire_at timestamptz,
  last_run_at  timestamptz,
  last_status  text,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sched_job_unique UNIQUE (tenant_id, app, name)
);

CREATE INDEX sched_jobs_due ON sched_jobs (next_fire_at) WHERE enabled;

CREATE TABLE sched_leases (
  job_id     uuid PRIMARY KEY REFERENCES sched_jobs(id) ON DELETE CASCADE,
  locked_until timestamptz NOT NULL
);

CREATE TABLE sched_runs (
  id         uuid PRIMARY KEY,
  job_id     uuid NOT NULL REFERENCES sched_jobs(id) ON DELETE CASCADE,
  tenant_id  uuid NOT NULL,
  fired_at   timestamptz NOT NULL DEFAULT now(),
  status     text NOT NULL CHECK (status IN ('ok', 'failed', 'skipped')),
  detail     text
);

CREATE INDEX sched_runs_job ON sched_runs (job_id, fired_at DESC);

CREATE TABLE sched_event_outbox (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  event_type   text NOT NULL,
  payload      jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

CREATE INDEX sched_event_outbox_unpublished ON sched_event_outbox (created_at)
  WHERE published_at IS NULL;
