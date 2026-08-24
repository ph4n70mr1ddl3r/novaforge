-- Integration branches (PHASE-6 §2): connector, webhook (both directions),
-- credential-reference, and import-mapping definitions join the kind-discriminated
-- app-definition documents — builder-authored metadata on the same draft/publish/
-- promotion path as every other branch.

ALTER TABLE md_definitions DROP CONSTRAINT md_definitions_kind_check;
ALTER TABLE md_definitions ADD CONSTRAINT md_definitions_kind_check
  CHECK (kind IN ('state_machine', 'sla', 'scheduled_job', 'workflow', 'report',
                  'dashboard', 'connector', 'webhook', 'credential', 'import_mapping'));
