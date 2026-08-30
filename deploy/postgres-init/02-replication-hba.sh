#!/bin/bash
# PITR leg (PHASE-8 §8, DR-drill finding D-1): the backup sidecar's
# pg_basebackup needs a replication entry in pg_hba.conf — the image's generated
# file carries only the regular host lines. Appended here at init time; stacks
# initialized before this script get the same line from the operator (documented
# in dr-restore-drill.md) with pg_reload_conf().
set -euo pipefail
echo "host replication postgres all scram-sha-256" >> "$PGDATA/pg_hba.conf"
