-- The gap-log branch (PHASE-7 §1 rule 2 / §8, PHASE-8 §3): the dogfood gap log
-- as versioned app metadata, so change-set review can render the entries a
-- promoting version resolves (the Phase 7 continuity surface). One kind row per
-- entry, keyed by the gap id.

ALTER TABLE md_definitions DROP CONSTRAINT md_definitions_kind_check;
ALTER TABLE md_definitions ADD CONSTRAINT md_definitions_kind_check
  CHECK (kind IN ('state_machine', 'sla', 'scheduled_job', 'workflow', 'report',
                  'dashboard', 'connector', 'webhook', 'credential', 'import_mapping',
                  'translation', 'gap_log'));
