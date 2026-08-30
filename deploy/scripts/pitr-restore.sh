#!/bin/bash
# PITR restore into the compose DR postgres (PHASE-8 §8 runbook leg; DR-drill
# finding D-1's documented mechanism): lay the nightly sidecar's physical base
# backup over the DR instance's data dir, point recovery at the WAL archive
# volume, and recover to the target time.
#
# Usage: deploy/scripts/pitr-restore.sh '2026-08-28 21:00:00+08'
# Preconditions: the backup sidecar has taken a base backup
# (novaforge_postgres-backups volume holds /base) and the primary's WAL archive
# volume (novaforge_wal-archive) covers the target time.
set -euo pipefail

TARGET_TIME="${1:?usage: pitr-restore.sh '<recovery_target_time>' e.g. '2026-08-28 21:00:00+08'}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/../compose/novaforge.yaml"
COMPOSE="${PODMAN_COMPOSE:-podman-compose}"   # this host's provider (podman compose delegates to Docker Desktop)
PG_IMAGE="docker.io/library/postgres:16.15"
# podman prefixes compose-project volumes with the project name (name: novaforge)
BACKUPS_VOL="novaforge_postgres-backups"
WAL_VOL="novaforge_wal-archive"
DR_VOL="novaforge_dr-postgres-data"

# newer providers (docker-compose) gate the dr profile; older ones (podman-compose
# 1.0.x) reject the flag outright but run profiled services unconditionally
if ! "$COMPOSE" -f "$COMPOSE_FILE" --profile dr up -d dr-postgres; then
  "$COMPOSE" -f "$COMPOSE_FILE" up -d dr-postgres
fi
podman stop novaforge-dr-postgres

# 1. the physical base becomes the DR data dir
podman run --rm -v "$BACKUPS_VOL":/backups:ro -v "$DR_VOL":/data "$PG_IMAGE" \
  bash -c 'rm -rf /data/* && cp -a /backups/base/. /data/ && rm -f /data/BACKUP_STAMP && chmod 700 /data'

# 2. recovery target + WAL source, then the recovery signal
#    (restore_command's path is the dr-postgres container's own /wal_archive mount)
podman run --rm -v "$WAL_VOL":/wal:ro -v "$DR_VOL":/data "$PG_IMAGE" \
  bash -c "printf \"restore_command = 'cp /wal_archive/%%f %%p'\nrecovery_target_time = '$TARGET_TIME'\nrecovery_target_action = 'promote'\n\" >> /data/postgresql.auto.conf && touch /data/recovery.signal"

# 3. recover
podman start novaforge-dr-postgres
echo "DR postgres recovering to $TARGET_TIME — follow: podman logs -f novaforge-dr-postgres"
echo "Recovery reached the target when the log prints 'database system is ready to accept connections'."
