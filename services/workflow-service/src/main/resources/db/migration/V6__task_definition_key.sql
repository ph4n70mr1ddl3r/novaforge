-- The per-task resolution variable (2026-08-31, seventeenth pass): the bridge wrote
-- ONE process-level `resolution` variable for every bridged task of an instance — a
-- parallel gateway's second completion overwrote the first, so the join's routing saw
-- only the last writer and one approver's outcome silently vanished. The link now
-- carries the engine task's definition key so each completion also writes
-- resolution_<key>; the bare `resolution` stays for single-task instances (authored
-- gateway conditions keep working).

ALTER TABLE wf_process_tasks ADD COLUMN task_definition_key text;
