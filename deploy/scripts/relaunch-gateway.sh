#!/usr/bin/env bash
# Relaunch the gateway JVM against the compose infra (the start-live-stack.sh
# pattern — plain nohup + pidfile; used when only the gateway needs a bounce,
# e.g. after rebuilding the jar with fresh SPA bundles).
#
# Honest-launch contract (twenty-ninth pass, same as start-live-stack.sh): a
# missing jar or a JVM that dies on boot is a FAILED relaunch with the boot
# log's tail — never a "started pid N" line.
set -u
cd "$(dirname "$0")/../.."
export NOVAFORGE_POSTGRES_PORT=5434
mkdir -p /tmp/novaforge/logs /tmp/novaforge/pids

jar="services/gateway/target/novaforge-gateway-0.1.0-SNAPSHOT.jar"
pidfile="/tmp/novaforge/pids/gateway.pid"
if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
  echo "gateway already running (pid $(cat "$pidfile"))"
  exit 0
fi
if [ ! -f "$jar" ]; then
  echo "RELAUNCH FAIL: gateway jar missing: $jar — build first (./mvnw -pl services/gateway -am install)" >&2
  exit 1
fi
nohup java -Xms32m -Xmx384m -jar "$jar" \
  > /tmp/novaforge/logs/gateway.out 2>&1 &
pid=$!
echo $pid > "$pidfile"
sleep 2
if kill -0 "$pid" 2>/dev/null; then
  echo "gateway started pid $pid"
else
  echo "RELAUNCH FAIL: gateway died within 2s — tail of /tmp/novaforge/logs/gateway.out:" >&2
  tail -n 5 /tmp/novaforge/logs/gateway.out >&2
  exit 1
fi
