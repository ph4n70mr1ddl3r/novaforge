-- md_definitions never grew the app FK its sibling child tables carry (V4): deleting
-- an app cascaded entities/pages/settings/versions/suites/runs/environments/promotions
-- but leaked every state machine, SLA, scheduled job, and workflow row forever —
-- including credential references the platform tracks for re-binding. Rows whose app
-- is already gone (deleted under the no-FK regime) are unreachable garbage — drop
-- them before the constraint, or the add would fail on any database holding orphans.

DELETE FROM md_definitions d
 WHERE NOT EXISTS (SELECT 1 FROM md_apps a WHERE a.id = d.app_id);

ALTER TABLE md_definitions
  ADD CONSTRAINT md_definitions_app_fk
  FOREIGN KEY (app_id) REFERENCES md_apps(id) ON DELETE CASCADE;
