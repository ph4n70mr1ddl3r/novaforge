-- Reporting branches (PHASE-5 §3/§5): report and dashboard definitions join the
-- kind-discriminated app-definition documents. Reports compile to Data Runtime
-- aggregate queries; dashboards are definition-only in v1 (loaded through the
-- published read, §2).

ALTER TABLE md_definitions DROP CONSTRAINT md_definitions_kind_check;
ALTER TABLE md_definitions ADD CONSTRAINT md_definitions_kind_check
  CHECK (kind IN ('state_machine', 'sla', 'scheduled_job', 'workflow', 'report', 'dashboard'));
