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

**The compose stack's implementation (landed 2026-08-28, closing the drill's
D-1/D-2/D-3/D-5 findings):**

| Concern | Compose mechanism |
|---------|-------------------|
| WAL archiving (PITR) | `postgres` runs `archive_mode=on`, archiving every segment to the `wal-archive` volume |
| Nightly snapshots + base backup | the `postgres-backup` sidecar (`deploy/compose/backup/nightly-backup.sh`): nightly `pg_dumpall` into `postgres-backups:/dumps` rotated to the newest `NOVAFORGE_BACKUP_KEEP` (7), plus a weekly physical `pg_basebackup` at `/base` for the PITR leg |
| MinIO bucket versioning | the `minio-init` one-shot enables `versioning` on the file bucket at stack bring-up |
| The DR targets | the compose `dr` profile (`--profile dr`): `dr-postgres` (:5435, WAL archive mounted read-only) + `dr-minio` (:9100/9101) |

## The PITR restore (the runbook's §6 mechanism)

```bash
# target time = the drill's chosen restore point (step 2 below)
deploy/scripts/pitr-restore.sh '2026-08-28 21:00:00+08'
```

The helper brings up the DR postgres, lays the sidecar's physical base backup
over its data dir, appends `restore_command = 'cp /wal_archive/%f %p'` +
`recovery_target_time` to `postgresql.auto.conf`, touches `recovery.signal`, and
starts the instance — recovery replays the archived WAL to the target and
promotes. The nightly-dump restore (the 2026-08-28 drill's executed leg) remains
the snapshot-granular alternative: replay the newest `dumps/novaforge-*.sql.gz`
into the DR instance with `psql`.

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
| Kafka | Topic retention sized for replay (7 d pinned explicitly — `KAFKA_LOG_RETENTION_HOURS=168`) | replay window |
| Realm (Keycloak) | Nightly realm export (`/admin/realms/novaforge/partialExport`) | ≤ 24 h |

> The compose stack's nightly `pg_dumpall` carries the Keycloak realm with it
> (the realm persists to the cluster's `keycloak` database — the 2026-08-28
> drill restored it from the dump); the standalone `partialExport` leg applies
> to staged deployments running Keycloak against its own store.
