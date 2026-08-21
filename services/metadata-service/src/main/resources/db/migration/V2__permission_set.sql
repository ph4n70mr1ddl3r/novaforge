-- PermissionSet branch (PHASE-2 §9): permissions are versioned, promoted metadata —
-- stored on the app document and snapshotted into published bundles.

ALTER TABLE md_apps ADD COLUMN permission_set jsonb NOT NULL DEFAULT '{}'::jsonb;
