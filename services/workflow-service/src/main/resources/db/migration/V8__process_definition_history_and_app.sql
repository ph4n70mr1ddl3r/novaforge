-- The redeploy-orphan and cross-app-removal fixes (the 2026-09-03 spec review;
-- both PHASE-4 §9 pins the registry never actually satisfied):
--
-- (1) Running instances finish on their own. The registry row's single
--     process_definition_id is OVERWRITTEN on every changed-content redeploy,
--     so an in-flight instance of the previous engine version that reached a
--     LATER user task resolved no registry row and was silently never bridged
--     (no wf_tasks row — the instance parked forever with no inbox surface).
--     definition_ids keeps every definition id the row has ever deployed; the
--     bridge resolves against any of them, so an old-version instance keeps
--     bridging until it finishes on its own version.
ALTER TABLE wf_process_deployments
    ADD COLUMN IF NOT EXISTS definition_ids jsonb NOT NULL DEFAULT '[]';

UPDATE wf_process_deployments
   SET definition_ids = to_jsonb(ARRAY[process_definition_id])
 WHERE definition_ids = '[]'
   AND process_definition_id IS NOT NULL;

-- (2) The removal cascade is per-app. Bridge rows carried only the bare
--     workflow id, but workflow ids are app-scoped (two apps may define the
--     same id — the deployment rows are app-qualified for exactly that reason):
--     removing one app's workflow cancelled the other app's same-keyed open
--     tasks. The bridge row now carries its owning app. Legacy rows backfill
--     only where unambiguous (one tenant, one same-keyed deployment); a
--     genuinely ambiguous legacy row keeps '' and stays cancellable by any
--     same-keyed removal — the old behavior, strictly better than never
--     cancelling — while every row written after this migration is exact.
ALTER TABLE wf_process_tasks
    ADD COLUMN IF NOT EXISTS app text NOT NULL DEFAULT '';

UPDATE wf_process_tasks p
   SET app = d.app
  FROM wf_tasks t, wf_process_deployments d
 WHERE t.id = p.task_id
   AND d.tenant_id = t.tenant_id
   AND d.workflow_id = p.workflow_id
   AND p.app = ''
   AND (SELECT count(*) FROM wf_process_deployments d2
         WHERE d2.tenant_id = t.tenant_id AND d2.workflow_id = p.workflow_id) = 1;
