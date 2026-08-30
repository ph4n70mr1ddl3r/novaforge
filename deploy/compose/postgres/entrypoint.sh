#!/bin/bash
# PITR entrypoint wrapper (PHASE-8 §8, DR-drill finding D-1): chown the WAL
# archive volume to the postgres user while still root — the image entrypoint's
# gosu drop happens next, and the postgres server user cannot write a fresh
# root-owned volume. The volume stays OUTSIDE PGDATA so pg_basebackup never
# copies the archive into the physical base. The plain-cp archive_command is
# idempotent: a retry after an interrupted copy heals instead of wedging.
set -e
chown postgres:postgres /wal_archive
exec docker-entrypoint.sh postgres \
  -c archive_mode=on \
  -c archive_command="cp %p /wal_archive/%f"
