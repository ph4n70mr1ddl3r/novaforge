# Runbook: DR backup + restore drill (PHASE-8 §8)

> The quarterly restore drill (§8): executed once in this phase as the acceptance.
> Scope pinned by the spec: Postgres PITR + nightly snapshots, MinIO bucket
> versioning + cross-store replication for the prod file set, Kafka retention sized
> for replay, and one real restore.

## Preconditions

- compose/Kind stack tagged with the nightly backup jobs (or the operator's cron
  equivalents): `pg_dump` nightly snapshots + Postgres WAL archiving for PITR;
  MinIO bucket versioning on the `novaforge` bucket with replication to the DR store.
- The DR target: a second Postgres instance + a second MinIO (the compose `dr`
  profile brings both up locally for the drill).

## The drill (quarterly)

1. **Snapshot inventory** — verify the last nightly snapshot exists and is younger
   than 24 h: `pg_dump` archive in the backup store; MinIO bucket versions listed
   (`mc ls --versions novaforge/files`).
2. **Point-in-time target** — pick the restore timestamp (the drill restores to
   10 minutes before "now"; PITR uses the WAL archive).
3. **Restore Postgres** — on the DR instance: restore the base snapshot, then
   `recovery_target_time = '<target>'`; every novaforge database comes back
   (`novaforge_metadata`, `novaforge_runtime`, `novaforge_audit`, …).
4. **Restore the file set** — replicate the versioned bucket to the DR MinIO;
   spot-check a presigned download of the newest attachment id from the restored
   metadata (the file service's DB rows must reference objects that exist).
5. **Replay Kafka** — topic retention must cover the RPO (default 7 days); the
   audit trail and the outbox consumers re-drive from committed offsets. Verify
   `novaforge-audit-service` consumer lag drains on the DR stack.
6. **Verify the exit criteria** — through the gateway on the DR stack:
   - `GET /api/v1/metadata/published-apps` lists every app that existed at the target;
   - one record CRUD round-trip per critical entity (an ERP journal entry read);
   - `GET /api/v1/audit/records/{id}` serves the trail for a pre-restore id.
7. **Record the drill** — duration, RPO achieved vs target, gaps found (these enter
   the Phase 7 gap-log discipline: triaged weekly, `accept | backlog | wontfix`).

## Secrets rotation (§8, exercised once in-phase)

Rotate one real connector credential through the Integration Service's secret store
(PHASE-6 §9): add the new version (two active), verify deliveries keep succeeding,
retire the old version, verify retirement left exactly one active secret and the
§11 item-1 rotation leg still holds (verification tries every active version).

## Backup configuration (the compose stack)

| Concern | Mechanism | RPO |
|---------|-----------|-----|
| Postgres | Nightly `pg_dump` snapshot + WAL archiving (PITR) | ≤ 5 min (WAL) |
| MinIO (files) | Bucket versioning + cross-store replication | ≤ 5 min |
| Kafka | Topic retention sized for replay (7 d default) | replay window |
| Realm (Keycloak) | Nightly realm export (`/admin/realms/novaforge/partialExport`) | ≤ 24 h |
