#!/bin/sh
# NovaForge nightly backup sidecar (PHASE-8 §8, DR-drill finding D-2): logical
# snapshots of the whole cluster (pg_dumpall — every database: metadata, runtime,
# audit, workflow, notification, scheduler, integration, file, Keycloak's realm
# store, platform) plus a weekly physical base backup for the PITR leg
# (finding D-1 — the WAL archive alone cannot recover without one).
#
# The Keycloak realm rides the dump (Keycloak persists to the cluster's
# `keycloak` database); the runbook's partialExport leg applies to staged
# deployments that run Keycloak against its own store.
#
# Overridables (service environment): NOVAFORGE_BACKUP_INTERVAL_SECONDS (default
# one night), NOVAFORGE_BACKUP_KEEP (dumps retained), NOVAFORGE_BACKUP_BASE_DAYS
# (physical base refresh cadence).
set -eu

KEEP="${NOVAFORGE_BACKUP_KEEP:-7}"
INTERVAL="${NOVAFORGE_BACKUP_INTERVAL_SECONDS:-86400}"
BASE_DAYS="${NOVAFORGE_BACKUP_BASE_DAYS:-7}"

echo "[backup] waiting for postgres..."
mkdir -p /backups/dumps
until pg_isready -h postgres -U postgres -q; do
  sleep 5
done
echo "[backup] postgres ready — dump every ${INTERVAL}s (keep ${KEEP}), base backup every ${BASE_DAYS}d"

while true; do
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"

  # physical base backup for PITR — refresh when absent or older than BASE_DAYS.
  # find -mmin +N matches files OLDER than N minutes, so the refresh condition is
  # NON-EMPTY on a stale stamp (the twenty-ninth pass: an inverted emptiness test
  # here refreshed only while the stamp was fresh and let the base backup die
  # silently forever the moment it aged past the cadence).
  if [ ! -f /backups/base/BACKUP_STAMP ] \
     || [ -n "$(find /backups/base/BACKUP_STAMP -mmin +"$((BASE_DAYS * 24 * 60))" 2>/dev/null)" ]; then
    echo "[backup] $(date -u) physical base backup -> /backups/base"
    rm -rf /backups/base.tmp
    if pg_basebackup -h postgres -U postgres -D /backups/base.tmp -Fp -Xs \
         --checkpoint=fast; then
      mv /backups/base.tmp /backups/base.new
      rm -rf /backups/base.old
      [ -d /backups/base ] && mv /backups/base /backups/base.old || true
      mv /backups/base.new /backups/base
      rm -rf /backups/base.old
      date -u > /backups/base/BACKUP_STAMP
      echo "[backup] $(date -u) base backup done"
    else
      echo "[backup] $(date -u) base backup FAILED" >&2
      rm -rf /backups/base.tmp
    fi
  fi

  # nightly logical snapshot
  target="/backups/dumps/novaforge-${stamp}.sql.gz"
  echo "[backup] $(date -u) pg_dumpall -> ${target}"
  if pg_dumpall -h postgres -U postgres | gzip > "${target}.tmp"; then
    mv "${target}.tmp" "${target}"
    echo "[backup] $(date -u) done: $(du -h "${target}" | cut -f1)"
    ls -1t /backups/dumps/novaforge-*.sql.gz 2>/dev/null | tail -n +"$((KEEP + 1))" \
      | while read -r old; do
          echo "[backup] rotating away ${old}"
          rm -f "${old}"
        done
  else
    echo "[backup] $(date -u) pg_dumpall FAILED" >&2
    rm -f "${target}.tmp"
  fi

  sleep "${INTERVAL}"
done
