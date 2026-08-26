#!/usr/bin/env bash
# Live-stack exercise launcher (Phases 4-8 exit legs): starts every NovaForge
# service as a host JVM against the compose infra, logs to /tmp/novaforge/logs.
set -u
cd "$(dirname "$0")/../.."
export NOVAFORGE_POSTGRES_PORT=5434
mkdir -p /tmp/novaforge/logs /tmp/novaforge/pids

declare -A SVCS=(
  [metadata]="services/metadata-service/target/novaforge-metadata-service-0.1.0-SNAPSHOT.jar:512m"
  [data-runtime]="services/data-runtime/api/target/novaforge-data-runtime-0.1.0-SNAPSHOT.jar:512m"
  [audit]="services/audit-service/target/novaforge-audit-service-0.1.0-SNAPSHOT.jar:384m"
  [script-engine]="services/script-engine/target/novaforge-script-engine-0.1.0-SNAPSHOT.jar:384m"
  [workflow]="services/workflow-service/target/novaforge-workflow-service-0.1.0-SNAPSHOT.jar:512m"
  [notification]="services/notification-service/target/novaforge-notification-service-0.1.0-SNAPSHOT.jar:384m"
  [scheduler]="services/scheduler-service/target/novaforge-scheduler-service-0.1.0-SNAPSHOT.jar:384m"
  [reporting]="services/reporting-service/target/novaforge-reporting-service-0.1.0-SNAPSHOT.jar:384m"
  [integration]="services/integration-service/target/novaforge-integration-service-0.1.0-SNAPSHOT.jar:512m"
  [file]="services/file-service/target/novaforge-file-service-0.1.0-SNAPSHOT.jar:384m"
  [gateway]="services/gateway/target/novaforge-gateway-0.1.0-SNAPSHOT.jar:384m"
)

for name in "${!SVCS[@]}"; do
  IFS=: read -r jar heap <<< "${SVCS[$name]}"
  pidfile="/tmp/novaforge/pids/$name.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "$name already running (pid $(cat "$pidfile"))"; continue
  fi
  nohup java -Xms64m -Xmx"$heap" -jar "$jar" > "/tmp/novaforge/logs/$name.out" 2>&1 &
  echo $! > "$pidfile"
  echo "$name started pid $(cat "$pidfile")"
done
