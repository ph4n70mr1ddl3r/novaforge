#!/usr/bin/env bash
# Live-stack exercise launcher (Phases 4-8 exit legs): starts every NovaForge
# service as a host JVM against the compose infra, logs to /tmp/novaforge/logs.
#
# Honest-launch contract (twenty-ninth pass): a service that cannot start is a
# FAILED launch, not a "started pid N" line — every jar must exist before
# anything boots (a partial stack masks boot-order defects), every launched JVM
# must still be alive after its first seconds (a bad port or config dies
# asynchronously into a .out file nobody reads), and the Keycloak auth-listener
# provider must actually be deployed into the mounted providers dir (the realm
# names `novaforge-auth`; a missing jar silences the whole auth.* audit trail
# with zero visible errors).
set -u
cd "$(dirname "$0")/../.."
export NOVAFORGE_POSTGRES_PORT=5434
mkdir -p /tmp/novaforge/logs /tmp/novaforge/pids

# --- Keycloak auth-listener provider (deploy/keycloak/auth-listener/README) ---
provider_dir=deploy/compose/keycloak/providers
listener_jar=$(ls deploy/keycloak/auth-listener/target/auth-listener-*.jar 2>/dev/null | head -n1)
deployed_jar=$(ls "$provider_dir"/auth-listener-*.jar 2>/dev/null | head -n1)
if [ -z "$listener_jar" ] || [ "$listener_jar" -ot "$deployed_jar" ] 2>/dev/null || [ ! -f "$deployed_jar" ]; then
  if [ ! -f "$listener_jar" ]; then
    echo "auth-listener: building deploy/keycloak/auth-listener..."
    ./mvnw -B -ntp -q -f deploy/keycloak/auth-listener/pom.xml package || {
      echo "FAIL: auth-listener build failed — the auth.* audit trail would be silent" >&2; exit 1; }
    listener_jar=$(ls deploy/keycloak/auth-listener/target/auth-listener-*.jar | head -n1)
  fi
  cp -f "$listener_jar" "$provider_dir"/
  echo "auth-listener: deployed $(basename "$listener_jar") -> $provider_dir/"
  echo "auth-listener: NOTE — if the keycloak container is already running, bounce it to load the provider"
else
  echo "auth-listener: provider already deployed ($(basename "$deployed_jar"))"
fi

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

# preflight: every jar must exist before anything boots — a partial stack
# starts and the missing member's absence reads as "already fine"
missing=0
for name in "${!SVCS[@]}"; do
  IFS=: read -r jar heap <<< "${SVCS[$name]}"
  if [ ! -f "$jar" ]; then
    echo "PREFLIGHT FAIL: $name jar missing: $jar" >&2
    missing=$((missing + 1))
  fi
done
if [ "$missing" -gt 0 ]; then
  echo "PREFLIGHT FAIL: $missing jar(s) missing — build first (./mvnw -DskipTests install), nothing started" >&2
  exit 1
fi

failures=0
for name in "${!SVCS[@]}"; do
  IFS=: read -r jar heap <<< "${SVCS[$name]}"
  pidfile="/tmp/novaforge/pids/$name.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "$name already running (pid $(cat "$pidfile"))"; continue
  fi
  nohup java -Xms64m -Xmx"$heap" -jar "$jar" > "/tmp/novaforge/logs/$name.out" 2>&1 &
  pid=$!
  echo $pid > "$pidfile"
  # a JVM that dies on boot (port taken, bad config, unsatisfied dependency)
  # does so within a few seconds — a launch that cannot stay alive for them is
  # reported as the failure it is, with the boot log's tail, not as a pid line
  sleep 2
  if kill -0 "$pid" 2>/dev/null; then
    echo "$name started pid $pid"
  else
    echo "LAUNCH FAIL: $name died within 2s — tail of /tmp/novaforge/logs/$name.out:" >&2
    tail -n 5 "/tmp/novaforge/logs/$name.out" >&2
    failures=$((failures + 1))
  fi
done
if [ "$failures" -gt 0 ]; then
  echo "LAUNCH FAIL: ${failures} service(s) failed to stay up" >&2
  exit 1
fi
