-- The promotion intent journal (2026-08-31, the eleventh pass): the first promotion
-- of an environment wrote nothing before the remote provisioning calls — a failure
-- between the provision and the pin left no trace, and every retry provisioned a
-- second sandbox tenant. The environment row now lands BEFORE the remote call
-- (status 'provisioning', the version already pinned, the environment identity blank);
-- completion fills the identity and flips it 'active'. Provisioning itself is keyed
-- on (tenant, app, env) — deterministic names, adopt-before-create — so a retry of a
-- dangling intent converges on the same environment instead of leaking one.

ALTER TABLE md_environments ADD COLUMN status text NOT NULL DEFAULT 'active';
ALTER TABLE md_environments ADD COLUMN provision_key uuid;

-- The boot reconcile records its realignments in the audited history; the kind CHECK
-- grows to admit them (a repair is neither a promote nor a rollback).
ALTER TABLE md_promotions DROP CONSTRAINT md_promotions_kind_check;
ALTER TABLE md_promotions ADD CONSTRAINT md_promotions_kind_check
  CHECK (kind IN ('promote', 'rollback', 'reconcile'));
